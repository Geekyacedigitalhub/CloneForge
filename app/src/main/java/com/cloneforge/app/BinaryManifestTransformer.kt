package com.cloneforge.app

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ManifestRewriteResult(
    val data: ByteArray,
    val packageChanged: Boolean,
    val labelChanged: Boolean,
    val stringReplacements: Map<String, String> = emptyMap()
)

class BinaryManifestTransformer {

    fun rewrite(
        manifest: ByteArray,
        sourcePackage: String,
        clonePackage: String,
        cloneLabel: String,
        versionName: String? = null,
        versionCode: Int? = null,
        preserveClasses: Boolean = false
    ): Result<ManifestRewriteResult> {
        return runCatching {
            require(sourcePackage.isNotBlank()) { "Source package is empty." }
            require(clonePackage.isNotBlank()) { "Clone package is empty." }
            require(cloneLabel.isNotBlank()) { "Clone label is empty." }

            val preparedManifest = ensureExtractNativeLibsAttribute(manifest)
            val editor = BinaryXmlEditor(preparedManifest)
            editor.replaceManifestPackage(sourcePackage, clonePackage, preserveClasses)
            editor.replaceApplicationLabel(cloneLabel)
            editor.clearSplitRequiredFlags()
            versionName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                editor.replaceManifestVersionName(it)
            }
            versionCode?.takeIf { it > 0 }?.let {
                editor.replaceManifestVersionCode(it)
            }

            editor.build()
        }
    }

    /**
     * Ensures `<application>` in the binary `AndroidManifest.xml` has the
     * `android:extractNativeLibs="true"` (`0x010104ea`) attribute.
     *
     * In split APKs with `minSdkVersion >= 23` (such as ChatGPT), `base.apk` has no
     * `.so` files of its own, so `aapt2` omits `android:extractNativeLibs` from
     * `<application>`, causing Android's `ParsingPackageUtils` to default
     * `extractNativeLibs` to `false` and fail with `INSTALL_FAILED_CPU_ABI_INCOMPATIBLE`
     * (`STATUS_FAILURE_INCOMPATIBLE`: "App not installed as app isn't compatible with your phone.")
     * when split `.so` libraries are merged into the standalone clone APK.
     */
    private fun ensureExtractNativeLibsAttribute(manifest: ByteArray): ByteArray {
        if (manifest.size < 8) return manifest
        val little = ByteOrder.LITTLE_ENDIAN

        fun readU16(data: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(data, offset, 2).order(little).short.toInt() and 0xffff

        fun readU32(data: ByteArray, offset: Int): Long =
            ByteBuffer.wrap(data, offset, 4).order(little).int.toLong() and 0xffffffffL

        fun putU16(data: ByteArray, offset: Int, value: Int) {
            ByteBuffer.wrap(data, offset, 2).order(little).putShort(value.toShort())
        }

        fun putU32(data: ByteArray, offset: Int, value: Long) {
            ByteBuffer.wrap(data, offset, 4).order(little).putInt(value.toInt())
        }

        fun align4(value: Int): Int = (value + 3) and -4

        fun length8Size(data: ByteArray, offset: Int): Int =
            if ((data[offset].toInt() and 0xff) and 0x80 == 0) 1 else 2

        fun readLength8(data: ByteArray, offset: Int): Int {
            val first = data[offset].toInt() and 0xff
            return if (first and 0x80 == 0) first
            else if (offset + 1 < data.size) ((first and 0x7f) shl 8) or (data[offset + 1].toInt() and 0xff)
            else 0
        }

        fun readString(data: ByteArray, start: Int, utf8: Boolean): String {
            if (start < 0 || start >= data.size) return ""
            return if (utf8) {
                var p = start
                val charLength = readLength8(data, p)
                p += length8Size(data, p)
                if (p >= data.size) return ""
                val byteLength = readLength8(data, p)
                p += length8Size(data, p)
                if (charLength < 0 || byteLength < 0 || p + byteLength > data.size) return ""
                String(data, p, byteLength, Charsets.UTF_8)
            } else {
                if (start + 2 > data.size) return ""
                val first = readU16(data, start)
                var p = start
                val length = if (first and 0x8000 != 0) {
                    if (p + 4 > data.size) return ""
                    p += 4
                    ((first and 0x7fff) shl 16) or readU16(data, start + 2)
                } else {
                    p += 2
                    first
                }
                if (p + length * 2 > data.size) return ""
                String(data, p, length * 2, Charsets.UTF_16LE)
            }
        }

        fun encodeString(value: String, utf8: Boolean): ByteArray {
            return if (utf8) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                val chars = value.length
                val charPrefix = if (chars < 0x80) byteArrayOf(chars.toByte()) else byteArrayOf(
                    (((chars shr 8) and 0x7f) or 0x80).toByte(),
                    (chars and 0xff).toByte()
                )
                val len = bytes.size
                val bytePrefix = if (len < 0x80) byteArrayOf(len.toByte()) else byteArrayOf(
                    (((len shr 8) and 0x7f) or 0x80).toByte(),
                    (len and 0xff).toByte()
                )
                charPrefix + bytePrefix + bytes + byteArrayOf(0)
            } else {
                val chars = value.toCharArray()
                val out = ByteArray(2 + chars.size * 2 + 2)
                putU16(out, 0, chars.size)
                chars.forEachIndexed { index, char ->
                    putU16(out, 2 + index * 2, char.code)
                }
                putU16(out, 2 + chars.size * 2, 0)
                out
            }
        }

        val xmlHeaderSize = readU16(manifest, 2)
        if (xmlHeaderSize < 8 || xmlHeaderSize > manifest.size) return manifest

        var poolOffset = -1
        var poolHeaderSize = 0
        var poolSize = 0
        var resMapOffset = -1
        var resMapHeaderSize = 0
        var resMapSize = 0

        val strings = mutableListOf<String>()
        val resourceIds = mutableListOf<Int>()
        var poolFlags = 0
        var poolUtf8 = false

        var scanOffset = xmlHeaderSize
        while (scanOffset + 8 <= manifest.size) {
            val type = readU16(manifest, scanOffset)
            val headerSize = readU16(manifest, scanOffset + 2)
            val size = readU32(manifest, scanOffset + 4).toInt()
            if (size <= 0 || scanOffset + size > manifest.size) break

            if (type == 0x0001 && poolOffset < 0) {
                val styleCount = readU32(manifest, scanOffset + 12).toInt()
                if (styleCount != 0) return manifest
                poolOffset = scanOffset
                poolHeaderSize = headerSize
                poolSize = size
                val count = readU32(manifest, scanOffset + 8).toInt()
                poolFlags = readU32(manifest, scanOffset + 16).toInt()
                val stringsStart = readU32(manifest, scanOffset + 20).toInt()
                val offsetsStart = scanOffset + headerSize
                val stringsBase = scanOffset + stringsStart
                poolUtf8 = poolFlags and 0x100 != 0
                repeat(count) { i ->
                    val strOff = readU32(manifest, offsetsStart + i * 4).toInt()
                    strings.add(readString(manifest, stringsBase + strOff, poolUtf8))
                }
            } else if (type == 0x0180 && resMapOffset < 0) {
                resMapOffset = scanOffset
                resMapHeaderSize = headerSize
                resMapSize = size
                val count = (size - headerSize) / 4
                for (i in 0 until count) {
                    resourceIds.add(readU32(manifest, scanOffset + headerSize + i * 4).toInt())
                }
            }
            scanOffset += size
        }

        if (poolOffset < 0) return manifest

        // Locate <application> start tag and check if extractNativeLibs (0x010104ea) already exists
        var appTagOffset = -1
        scanOffset = xmlHeaderSize
        while (scanOffset + 8 <= manifest.size) {
            val type = readU16(manifest, scanOffset)
            val headerSize = readU16(manifest, scanOffset + 2)
            val size = readU32(manifest, scanOffset + 4).toInt()
            if (size <= 0 || scanOffset + size > manifest.size) break

            if (type == 0x0102 && scanOffset + 36 <= manifest.size) {
                val nameIdx = readU32(manifest, scanOffset + 20).toInt()
                if (strings.getOrNull(nameIdx) == "application") {
                    appTagOffset = scanOffset
                    val attrStart = readU16(manifest, scanOffset + 24)
                    val attrSize = readU16(manifest, scanOffset + 26)
                    val attrCount = readU16(manifest, scanOffset + 28)
                    var attr = scanOffset + headerSize + attrStart
                    repeat(attrCount) {
                        if (attr + attrSize <= scanOffset + size && attrSize >= 20) {
                            val attrNameIdx = readU32(manifest, attr + 4).toInt()
                            if (strings.getOrNull(attrNameIdx) == "extractNativeLibs" ||
                                resourceIds.getOrNull(attrNameIdx) == 0x010104ea
                            ) {
                                // Already present; BinaryXmlEditor.clearSplitRequiredFlags() will set it to true
                                return manifest
                            }
                        }
                        attr += attrSize
                    }
                    break
                }
            }
            scanOffset += size
        }

        if (appTagOffset < 0) return manifest

        // Determine or allocate the attribute name index for extractNativeLibs (0x010104ea)
        val extractResId = 0x010104ea
        var shiftedFromIndex = -1
        val extractAttrNameIndex: Int = if (resMapOffset >= 0) {
            val existingResIdx = resourceIds.indexOf(extractResId)
            if (existingResIdx >= 0) {
                existingResIdx
            } else {
                val insertIdx = resourceIds.size.coerceAtMost(strings.size)
                strings.add(insertIdx, "extractNativeLibs")
                resourceIds.add(extractResId)
                shiftedFromIndex = insertIdx
                insertIdx
            }
        } else {
            val existingStrIdx = strings.indexOf("extractNativeLibs")
            if (existingStrIdx >= 0) {
                existingStrIdx
            } else {
                strings.add("extractNativeLibs")
                strings.lastIndex
            }
        }

        fun shiftIdx(idx: Int): Int {
            if (shiftedFromIndex < 0) return idx
            if (idx == -1) return -1
            return if (idx >= shiftedFromIndex) idx + 1 else idx
        }

        var androidNsIndex = strings.indexOf("http://schemas.android.com/apk/res/android")
        if (androidNsIndex < 0) {
            strings.add("http://schemas.android.com/apk/res/android")
            androidNsIndex = strings.lastIndex
        }

        // Rebuild string pool chunk (0x0001)
        val encodedStrings = strings.map { encodeString(it, poolUtf8) }
        val stringOffsets = IntArray(strings.size)
        var strCursor = 0
        encodedStrings.forEachIndexed { idx, bytes ->
            stringOffsets[idx] = strCursor
            strCursor += bytes.size
        }
        val stringsStart = poolHeaderSize + strings.size * 4
        val newPoolSize = align4(stringsStart + strCursor)
        val newPoolChunk = ByteArray(newPoolSize)
        putU16(newPoolChunk, 0, 0x0001)
        putU16(newPoolChunk, 2, poolHeaderSize)
        putU32(newPoolChunk, 4, newPoolSize.toLong())
        putU32(newPoolChunk, 8, strings.size.toLong())
        putU32(newPoolChunk, 12, 0)
        putU32(newPoolChunk, 16, (poolFlags and 0x1.inv()).toLong())
        putU32(newPoolChunk, 20, stringsStart.toLong())
        putU32(newPoolChunk, 24, 0)
        stringOffsets.forEachIndexed { idx, off ->
            putU32(newPoolChunk, poolHeaderSize + idx * 4, off.toLong())
        }
        encodedStrings.forEachIndexed { idx, bytes ->
            System.arraycopy(bytes, 0, newPoolChunk, stringsStart + stringOffsets[idx], bytes.size)
        }

        val outChunks = ByteArrayOutputStream(manifest.size + 64)
        // Reserve 8-byte XML header
        outChunks.write(manifest, 0, xmlHeaderSize)

        var chunkCursor = xmlHeaderSize
        while (chunkCursor + 8 <= manifest.size) {
            val type = readU16(manifest, chunkCursor)
            val headerSize = readU16(manifest, chunkCursor + 2)
            val size = readU32(manifest, chunkCursor + 4).toInt()
            if (size <= 0 || chunkCursor + size > manifest.size) break

            when {
                chunkCursor == poolOffset -> {
                    outChunks.write(newPoolChunk)
                }
                chunkCursor == resMapOffset -> {
                    val newResMapSize = resMapHeaderSize + resourceIds.size * 4
                    val resChunk = ByteArray(newResMapSize)
                    putU16(resChunk, 0, 0x0180)
                    putU16(resChunk, 2, resMapHeaderSize)
                    putU32(resChunk, 4, newResMapSize.toLong())
                    resourceIds.forEachIndexed { i, id ->
                        putU32(resChunk, resMapHeaderSize + i * 4, id.toLong() and 0xffffffffL)
                    }
                    outChunks.write(resChunk)
                }
                else -> {
                    val chunkBytes = manifest.copyOfRange(chunkCursor, chunkCursor + size)
                    if (shiftedFromIndex >= 0 && chunkBytes.size >= 16) {
                        // Shift node comment string ref at offset 12
                        putU32(chunkBytes, 12, shiftIdx(readU32(chunkBytes, 12).toInt()).toLong() and 0xffffffffL)
                        when (type) {
                            0x0100, 0x0101, 0x0103 -> {
                                if (chunkBytes.size >= 24) {
                                    putU32(chunkBytes, 16, shiftIdx(readU32(chunkBytes, 16).toInt()).toLong() and 0xffffffffL)
                                    putU32(chunkBytes, 20, shiftIdx(readU32(chunkBytes, 20).toInt()).toLong() and 0xffffffffL)
                                }
                            }
                            0x0104 -> {
                                if (chunkBytes.size >= 28) {
                                    putU32(chunkBytes, 16, shiftIdx(readU32(chunkBytes, 16).toInt()).toLong() and 0xffffffffL)
                                    val dataType = chunkBytes[23].toInt() and 0xff
                                    if (dataType == 0x03) {
                                        putU32(chunkBytes, 24, shiftIdx(readU32(chunkBytes, 24).toInt()).toLong() and 0xffffffffL)
                                    }
                                }
                            }
                            0x0102 -> {
                                if (chunkBytes.size >= 36) {
                                    putU32(chunkBytes, 16, shiftIdx(readU32(chunkBytes, 16).toInt()).toLong() and 0xffffffffL)
                                    putU32(chunkBytes, 20, shiftIdx(readU32(chunkBytes, 20).toInt()).toLong() and 0xffffffffL)
                                    val attrStart = readU16(chunkBytes, 24)
                                    val attrSize = readU16(chunkBytes, 26)
                                    val attrCount = readU16(chunkBytes, 28)
                                    var attrOff = headerSize + attrStart
                                    repeat(attrCount) {
                                        if (attrOff + attrSize <= chunkBytes.size && attrSize >= 20) {
                                            putU32(chunkBytes, attrOff, shiftIdx(readU32(chunkBytes, attrOff).toInt()).toLong() and 0xffffffffL)
                                            putU32(chunkBytes, attrOff + 4, shiftIdx(readU32(chunkBytes, attrOff + 4).toInt()).toLong() and 0xffffffffL)
                                            putU32(chunkBytes, attrOff + 8, shiftIdx(readU32(chunkBytes, attrOff + 8).toInt()).toLong() and 0xffffffffL)
                                            val dataType = chunkBytes[attrOff + 15].toInt() and 0xff
                                            if (dataType == 0x03) {
                                                putU32(chunkBytes, attrOff + 16, shiftIdx(readU32(chunkBytes, attrOff + 16).toInt()).toLong() and 0xffffffffL)
                                            }
                                        }
                                        attrOff += attrSize
                                    }
                                }
                            }
                        }
                    }

                    if (chunkCursor == appTagOffset && chunkBytes.size >= 36) {
                        val attrStart = readU16(chunkBytes, 24)
                        val attrSize = readU16(chunkBytes, 26)
                        val attrCount = readU16(chunkBytes, 28)
                        if (attrSize == 20) {
                            var insertPos = attrCount
                            if (resourceIds.isNotEmpty()) {
                                for (i in 0 until attrCount) {
                                    val attrOff = headerSize + attrStart + i * attrSize
                                    val attrNameIdx = readU32(chunkBytes, attrOff + 4).toInt()
                                    val resId = resourceIds.getOrNull(attrNameIdx) ?: 0
                                    val unsignedResId = resId.toLong() and 0xffffffffL
                                    if (resId == 0 || unsignedResId > (extractResId.toLong() and 0xffffffffL)) {
                                        insertPos = i
                                        break
                                    }
                                }
                            }

                            val newAttr = ByteArray(20)
                            putU32(newAttr, 0, androidNsIndex.toLong() and 0xffffffffL)
                            putU32(newAttr, 4, extractAttrNameIndex.toLong() and 0xffffffffL)
                            putU32(newAttr, 8, -1L) // rawValue = -1
                            putU16(newAttr, 12, 8)  // typedValue.size = 8
                            newAttr[14] = 0         // res0 = 0
                            newAttr[15] = 0x12      // TYPE_INT_BOOLEAN
                            putU32(newAttr, 16, -1L) // true

                            val insertByteOffset = headerSize + attrStart + insertPos * attrSize
                            val expanded = ByteArray(chunkBytes.size + 20)
                            System.arraycopy(chunkBytes, 0, expanded, 0, insertByteOffset)
                            System.arraycopy(newAttr, 0, expanded, insertByteOffset, 20)
                            System.arraycopy(
                                chunkBytes,
                                insertByteOffset,
                                expanded,
                                insertByteOffset + 20,
                                chunkBytes.size - insertByteOffset
                            )

                            putU32(expanded, 4, expanded.size.toLong())
                            putU16(expanded, 28, attrCount + 1)

                            val idIndex = readU16(expanded, 30)
                            val classIndex = readU16(expanded, 32)
                            val styleIndex = readU16(expanded, 34)
                            if (idIndex > insertPos) putU16(expanded, 30, idIndex + 1)
                            if (classIndex > insertPos) putU16(expanded, 32, classIndex + 1)
                            if (styleIndex > insertPos) putU16(expanded, 34, styleIndex + 1)

                            outChunks.write(expanded)
                        } else {
                            outChunks.write(chunkBytes)
                        }
                    } else {
                        outChunks.write(chunkBytes)
                    }
                }
            }

            chunkCursor += size
        }

        val finalBytes = outChunks.toByteArray()
        if (finalBytes.size >= 8) {
            putU32(finalBytes, 4, finalBytes.size.toLong())
        }
        return finalBytes
    }
}

private class BinaryXmlEditor(private val original: ByteArray) {

    private val little = ByteOrder.LITTLE_ENDIAN
    private val strings = mutableListOf<String>()
    private val stringIndexes = mutableMapOf<String, Int>()
    private val resourceIds = mutableListOf<Int>()
    private var stringPoolChunk: Chunk? = null
    private val replacements = mutableMapOf<Int, Int>()
    private val intReplacements = mutableMapOf<Int, Int>()
    private val booleanReplacements = mutableMapOf<Int, Boolean>()
    private val explicitStringReplacements = mutableMapOf<String, String>()
    private var packageReplacement = false
    private var labelReplacement = false

    data class Chunk(val offset: Int, val type: Int, val headerSize: Int, val size: Int)

    fun replaceManifestPackage(source: String, target: String, preserveClasses: Boolean = false) {
        val index = findString(source)
        require(index >= 0) { "Source package was not found in the manifest string pool." }

        val sourcePrefix = "$source."
        val targetPrefix = "$target."
        if (preserveClasses) {
            expandRelativeComponentNames(source)
            rewriteProviderAuthoritiesAndPermissions(source, target)
            val dynPerm = "$source.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            val dynPermTarget = "$target.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            explicitStringReplacements[dynPerm] = dynPermTarget
            for (i in strings.indices) {
                val s = strings[i]
                val mapped = explicitStringReplacements[s]
                if (mapped != null && mapped != s) {
                    strings[i] = mapped
                    stringIndexes[mapped] = i
                }
            }
        } else {
            for (i in strings.indices) {
                val current = strings[i]
                if (current == source) {
                    strings[i] = target
                    stringIndexes[target] = i
                    packageReplacement = true
                } else if (current.contains(';')) {
                    val parts = current.split(';')
                    var changed = false
                    val updatedParts = parts.map { part ->
                        when {
                            part == target || part.startsWith(targetPrefix) -> part
                            part == source -> {
                                changed = true
                                target
                            }
                            part.startsWith(sourcePrefix) -> {
                                changed = true
                                targetPrefix + part.removePrefix(sourcePrefix)
                            }
                            else -> part
                        }
                    }
                    if (changed) {
                        val updated = updatedParts.joinToString(";")
                        strings[i] = updated
                        stringIndexes[updated] = i
                        packageReplacement = true
                    }
                } else if (current != target && !current.startsWith(targetPrefix) && current.startsWith(sourcePrefix)) {
                    val updated = targetPrefix + current.removePrefix(sourcePrefix)
                    strings[i] = updated
                    stringIndexes[updated] = i
                    packageReplacement = true
                }
            }

            stringIndexes.remove(source)
            rewriteProviderAuthoritiesAndPermissions(source, target)
        }

        val targetIndex = indexFor(target)

        findStartTagAttribute("manifest", "package")?.let {
            replacements[it] = targetIndex
            packageReplacement = true
        } ?: error("Manifest package attribute was not found.")
    }

    private fun expandRelativeComponentNames(sourcePackage: String) {
        ensureStringPool()
        val componentTags = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
        var offset = readU16(original, 2)
        while (offset + 8 <= original.size) {
            val type = readU16(original, offset)
            val headerSize = readU16(original, offset + 2)
            val size = readU32(original, offset + 4).toInt()
            if (size <= 0 || offset + size > original.size) break

            if (type == 0x0102 && offset + 36 <= original.size) {
                val tagName = strings.getOrNull(readU32(original, offset + 20).toInt())
                if (tagName in componentTags) {
                    val attributeStart = readU16(original, offset + 24)
                    val attributeSize = readU16(original, offset + 26)
                    val attributeCount = readU16(original, offset + 28)
                    var attr = offset + headerSize + attributeStart

                    repeat(attributeCount) {
                        if (attr + attributeSize > offset + size || attributeSize < 20) return@repeat
                        val attrNameIndex = readU32(original, attr + 4).toInt()
                        if (matchesAttribute(attrNameIndex, "name", 0x01010003) ||
                            matchesAttribute(attrNameIndex, "targetActivity", 0x01010202)
                        ) {
                            val rawValueIdx = readU32(original, attr + 8).toInt()
                            val dataType = original[attr + 15].toInt() and 0xff
                            val dataIdx = readU32(original, attr + 16).toInt()
                            val strIdx = if (rawValueIdx in strings.indices) rawValueIdx else if (dataType == 0x03 && dataIdx in strings.indices) dataIdx else -1
                            if (strIdx in strings.indices) {
                                val current = strings[strIdx]
                                val expanded = when {
                                    current.startsWith(".") -> "$sourcePackage$current"
                                    current.isNotBlank() && !current.contains(".") -> "$sourcePackage.$current"
                                    else -> current
                                }
                                if (expanded != current) {
                                    replacements[attr + 8] = indexFor(expanded)
                                }
                            }
                        }
                        attr += attributeSize
                    }
                }
            }
            offset += size
        }
    }

    fun replaceApplicationLabel(label: String) {
        val targetIndex = indexFor(label)

        val attr = findStartTagAttribute("application", "label")
            ?: return

        replacements[attr] = targetIndex
        labelReplacement = true
    }

    fun replaceManifestVersionName(versionName: String) {
        val targetIndex = indexFor(versionName)
        val attr = findStartTagAttribute("manifest", "versionName") ?: return
        replacements[attr] = targetIndex
    }

    fun replaceManifestVersionCode(versionCode: Int) {
        val attr = findStartTagAttribute("manifest", "versionCode") ?: return
        intReplacements[attr] = versionCode
    }

    fun clearSplitRequiredFlags() {
        ensureStringPool()
        for (i in strings.indices) {
            when (strings[i]) {
                "com.android.vending.splits.required" -> {
                    strings[i] = "com.android.vending.splits.disabled"
                    stringIndexes[strings[i]] = i
                }
                "com.android.vending.splits" -> {
                    strings[i] = "com.android.vending.split0"
                    stringIndexes[strings[i]] = i
                }
                "com.android.vending.derived.apk.id" -> {
                    strings[i] = "com.android.vending.derived.apk.no"
                    stringIndexes[strings[i]] = i
                }
            }
        }
        findStartTagAttribute("manifest", "isSplitRequired", 0x01010591)?.let {
            booleanReplacements[it] = false
        }
        findStartTagAttribute("application", "isSplitRequired", 0x01010591)?.let {
            booleanReplacements[it] = false
        }
        findStartTagAttribute("manifest", "requiredSplitTypes", 0x0101064e)?.let {
            replacements[it] = indexFor("")
        }
        findStartTagAttribute("manifest", "splitTypes", 0x0101064f)?.let {
            replacements[it] = indexFor("")
        }
        findStartTagAttribute("application", "requiredSplitTypes", 0x0101064e)?.let {
            replacements[it] = indexFor("")
        }
        findStartTagAttribute("application", "splitTypes", 0x0101064f)?.let {
            replacements[it] = indexFor("")
        }
        findStartTagAttribute("application", "extractNativeLibs", 0x010104ea)?.let {
            booleanReplacements[it] = true
        }
    }

    private fun rewriteProviderAuthoritiesAndPermissions(source: String, target: String) {
        ensureStringPool()
        val sourcePrefix = "$source."
        val targetPrefix = "$target."

        var offset = readU16(original, 2)
        while (offset + 8 <= original.size) {
            val type = readU16(original, offset)
            val headerSize = readU16(original, offset + 2)
            val size = readU32(original, offset + 4).toInt()
            if (size <= 0 || offset + size > original.size) break

            if (type == 0x0102 && offset + 36 <= original.size) {
                val tagName = strings.getOrNull(readU32(original, offset + 20).toInt())
                val attributeStart = readU16(original, offset + 24)
                val attributeSize = readU16(original, offset + 26)
                val attributeCount = readU16(original, offset + 28)
                var attr = offset + headerSize + attributeStart

                if (tagName == "provider") {
                    repeat(attributeCount) {
                        if (attr + attributeSize > offset + size || attributeSize < 20) return@repeat
                        val attrNameIndex = readU32(original, attr + 4).toInt()
                        if (matchesAttribute(attrNameIndex, "authorities", 0x01010018)) {
                            val rawValueIdx = readU32(original, attr + 8).toInt()
                            val dataType = original[attr + 15].toInt() and 0xff
                            val dataIdx = readU32(original, attr + 16).toInt()
                            val strIdx = if (rawValueIdx in strings.indices) rawValueIdx else if (dataType == 0x03 && dataIdx in strings.indices) dataIdx else -1
                            if (strIdx in strings.indices) {
                                val current = strings[strIdx]
                                val updated = current.split(';').joinToString(";") { part ->
                                    val mapped = when {
                                        part.isBlank() || part == target || part.startsWith(targetPrefix) -> part
                                        part == source -> target
                                        part.startsWith(sourcePrefix) -> targetPrefix + part.removePrefix(sourcePrefix)
                                        else -> targetPrefix + part
                                    }
                                    if (mapped != part && part.isNotBlank()) {
                                        explicitStringReplacements[part] = mapped
                                        explicitStringReplacements["content://$part"] = "content://$mapped"
                                    }
                                    mapped
                                }
                                if (updated != current) {
                                    strings[strIdx] = updated
                                    stringIndexes[updated] = strIdx
                                }
                            }
                        }
                        attr += attributeSize
                    }
                } else if (tagName == "permission" || tagName == "permission-group" || tagName == "permission-tree") {
                    repeat(attributeCount) {
                        if (attr + attributeSize > offset + size || attributeSize < 20) return@repeat
                        val attrNameIndex = readU32(original, attr + 4).toInt()
                        if (matchesAttribute(attrNameIndex, "name", 0x01010003)) {
                            val rawValueIdx = readU32(original, attr + 8).toInt()
                            val dataType = original[attr + 15].toInt() and 0xff
                            val dataIdx = readU32(original, attr + 16).toInt()
                            val strIdx = if (rawValueIdx in strings.indices) rawValueIdx else if (dataType == 0x03 && dataIdx in strings.indices) dataIdx else -1
                            if (strIdx in strings.indices) {
                                val current = strings[strIdx]
                                if (current.isNotBlank() && !current.startsWith("android.permission.") &&
                                    current != target && !current.startsWith(targetPrefix)
                                ) {
                                    val updated = if (current.startsWith(sourcePrefix)) {
                                        targetPrefix + current.removePrefix(sourcePrefix)
                                    } else {
                                        targetPrefix + current
                                    }
                                    explicitStringReplacements[current] = updated
                                    strings[strIdx] = updated
                                    stringIndexes[updated] = strIdx
                                }
                            }
                        }
                        attr += attributeSize
                    }
                }
            }
            offset += size
        }
    }

    fun build(): ManifestRewriteResult {
        val pool = stringPoolChunk ?: error("Android string pool not found.")
        val newPool = buildStringPool(pool)

        val before = original.copyOfRange(0, pool.offset)
        val after = original.copyOfRange(pool.offset + pool.size, original.size)

        val result = ByteArray(before.size + newPool.size + after.size)
        System.arraycopy(before, 0, result, 0, before.size)
        System.arraycopy(newPool, 0, result, before.size, newPool.size)
        System.arraycopy(after, 0, result, before.size + newPool.size, after.size)

        if (result.size >= 8) {
            putU32(result, 4, result.size.toLong())
        }

        val delta = newPool.size - pool.size

        replacements.forEach { (absoluteValueOffset, newIndex) ->
            val adjusted = if (absoluteValueOffset >= pool.offset + pool.size) {
                absoluteValueOffset + delta
            } else {
                absoluteValueOffset
            }

            putU32(result, adjusted, newIndex.toLong())
            result[adjusted + 7] = 0x03
            putU32(result, adjusted + 8, newIndex.toLong())
        }

        intReplacements.forEach { (absoluteValueOffset, intValue) ->
            val adjusted = if (absoluteValueOffset >= pool.offset + pool.size) {
                absoluteValueOffset + delta
            } else {
                absoluteValueOffset
            }

            putU32(result, adjusted, -1L)
            result[adjusted + 7] = 0x10
            putU32(result, adjusted + 8, intValue.toLong() and 0xffffffffL)
        }

        booleanReplacements.forEach { (absoluteValueOffset, boolValue) ->
            val adjusted = if (absoluteValueOffset >= pool.offset + pool.size) {
                absoluteValueOffset + delta
            } else {
                absoluteValueOffset
            }

            putU32(result, adjusted, -1L)
            result[adjusted + 7] = 0x12
            putU32(result, adjusted + 8, if (boolValue) -1L else 0L)
        }

        return ManifestRewriteResult(
            data = result,
            packageChanged = packageReplacement,
            labelChanged = labelReplacement,
            stringReplacements = explicitStringReplacements.toMap()
        )
    }

    private fun findString(value: String): Int {
        ensureStringPool()
        return stringIndexes[value] ?: -1
    }

    private fun indexFor(value: String): Int {
        ensureStringPool()
        return stringIndexes[value] ?: run {
            strings.add(value)
            val index = strings.lastIndex
            stringIndexes[value] = index
            index
        }
    }

    private fun ensureStringPool() {
        if (stringPoolChunk != null) return

        var offset = readU16(original, 2)
        while (offset + 8 <= original.size) {
            val type = readU16(original, offset)
            val headerSize = readU16(original, offset + 2)
            val size = readU32(original, offset + 4).toInt()

            if (size <= 0 || offset + size > original.size) break

            if (type == 0x0001 && stringPoolChunk == null) {
                stringPoolChunk = Chunk(offset, type, headerSize, size)
                readStringPool(offset, headerSize)
            } else if (type == 0x0180 && resourceIds.isEmpty()) {
                val count = (size - headerSize) / 4
                for (i in 0 until count) {
                    resourceIds.add(readU32(original, offset + headerSize + i * 4).toInt())
                }
            }

            offset += size
        }

        if (stringPoolChunk == null) {
            error("Android string pool was not found.")
        }
    }

    private fun readStringPool(offset: Int, headerSize: Int) {
        val count = readU32(original, offset + 8).toInt()
        val flags = readU32(original, offset + 16).toInt()
        val stringsStart = readU32(original, offset + 20).toInt()
        val offsetsStart = offset + headerSize
        val stringsBase = offset + stringsStart
        val utf8 = flags and 0x100 != 0

        repeat(count) { i ->
            val stringOffset = readU32(original, offsetsStart + i * 4).toInt()
            strings.add(readString(original, stringsBase + stringOffset, utf8))
            stringIndexes[strings.last()] = i
        }
    }

    private fun matchesAttribute(attrNameIndex: Int, attribute: String, expectedResId: Int = 0): Boolean {
        if (strings.getOrNull(attrNameIndex) == attribute) return true
        if (expectedResId != 0 && resourceIds.getOrNull(attrNameIndex) == expectedResId) return true
        return false
    }

    private fun findStartTagAttribute(tag: String, attribute: String, expectedResId: Int = 0): Int? {
        ensureStringPool()

        val defaultResId = if (expectedResId != 0) expectedResId else when (attribute) {
            "label" -> 0x01010001
            "name" -> 0x01010003
            "authorities" -> 0x01010018
            "versionCode" -> 0x0101021b
            "versionName" -> 0x0101021c
            "extractNativeLibs" -> 0x010104ea
            "isSplitRequired" -> 0x01010591
            "requiredSplitTypes" -> 0x0101064e
            "splitTypes" -> 0x0101064f
            else -> 0
        }

        var offset = readU16(original, 2)

        while (offset + 8 <= original.size) {
            val type = readU16(original, offset)
            val headerSize = readU16(original, offset + 2)
            val size = readU32(original, offset + 4).toInt()

            if (size <= 0 || offset + size > original.size) break

            if (type == 0x0102 && offset + 36 <= original.size) {
                val nameIndex = readU32(original, offset + 20).toInt()
                if (strings.getOrNull(nameIndex) == tag) {
                    val attributeStart = readU16(original, offset + 24)
                    val attributeSize = readU16(original, offset + 26)
                    val attributeCount = readU16(original, offset + 28)

                    var attr = offset + headerSize + attributeStart

                    repeat(attributeCount) {
                        if (attr + attributeSize > offset + size || attributeSize < 20) return@repeat

                        val attrNameIndex = readU32(original, attr + 4).toInt()
                        if (matchesAttribute(attrNameIndex, attribute, defaultResId)) {
                            return attr + 8
                        }

                        attr += attributeSize
                    }
                }
            }

            offset += size
        }

        return null
    }

    private fun buildStringPool(pool: Chunk): ByteArray {
        val flags = readU32(original, pool.offset + 16).toInt()
        val utf8 = flags and 0x100 != 0
        val styleCount = readU32(original, pool.offset + 12).toInt()

        require(styleCount == 0) {
            "Styled Android string pools are not supported by this safe writer yet."
        }

        val headerSize = pool.headerSize
        val encoded = strings.map { encodeString(it, utf8) }
        val offsets = IntArray(strings.size)
        var cursor = 0

        encoded.forEachIndexed { index, bytes ->
            offsets[index] = cursor
            cursor += bytes.size
        }

        val stringsStart = headerSize + strings.size * 4
        val unpaddedSize = stringsStart + cursor
        val chunkSize = align4(unpaddedSize)

        val out = ByteArray(chunkSize)
        putU16(out, 0, 0x0001)
        putU16(out, 2, headerSize)
        putU32(out, 4, chunkSize.toLong())
        putU32(out, 8, strings.size.toLong())
        putU32(out, 12, 0)
        putU32(out, 16, (flags and 0x1.inv()).toLong())
        putU32(out, 20, stringsStart.toLong())
        putU32(out, 24, 0)

        offsets.forEachIndexed { index, value ->
            putU32(out, headerSize + index * 4, value.toLong())
        }

        encoded.forEachIndexed { index, bytes ->
            System.arraycopy(bytes, 0, out, stringsStart + offsets[index], bytes.size)
        }

        return out
    }

    private fun encodeString(value: String, utf8: Boolean): ByteArray {
        return if (utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val chars = value.length
            val charPrefix = if (chars < 0x80) byteArrayOf(chars.toByte()) else byteArrayOf(
                (((chars shr 8) and 0x7f) or 0x80).toByte(),
                (chars and 0xff).toByte()
            )
            val len = bytes.size
            val bytePrefix = if (len < 0x80) byteArrayOf(len.toByte()) else byteArrayOf(
                (((len shr 8) and 0x7f) or 0x80).toByte(),
                (len and 0xff).toByte()
            )

            charPrefix + bytePrefix + bytes + byteArrayOf(0)
        } else {
            val chars = value.toCharArray()
            val out = ByteArray(2 + chars.size * 2 + 2)
            putU16(out, 0, chars.size)
            chars.forEachIndexed { index, char ->
                putU16(out, 2 + index * 2, char.code)
            }
            putU16(out, 2 + chars.size * 2, 0)
            out
        }
    }

    private fun readString(data: ByteArray, start: Int, utf8: Boolean): String {
        if (start < 0 || start >= data.size) return ""

        return if (utf8) {
            var p = start
            val charLength = readLength8(data, p)
            p += length8Size(data, p)
            if (p >= data.size) return ""
            val byteLength = readLength8(data, p)
            p += length8Size(data, p)
            if (charLength < 0 || byteLength < 0 || p + byteLength > data.size) return ""
            String(data, p, byteLength, Charsets.UTF_8)
        } else {
            if (start + 2 > data.size) return ""
            val first = readU16(data, start)
            var p = start
            val length = if (first and 0x8000 != 0) {
                if (p + 4 > data.size) return ""
                p += 4
                ((first and 0x7fff) shl 16) or readU16(data, start + 2)
            } else {
                p += 2
                first
            }
            if (p + length * 2 > data.size) return ""
            String(data, p, length * 2, Charsets.UTF_16LE)
        }
    }

    private fun readLength8(data: ByteArray, offset: Int): Int {
        val first = data[offset].toInt() and 0xff
        return if (first and 0x80 == 0) first
        else if (offset + 1 < data.size) ((first and 0x7f) shl 8) or (data[offset + 1].toInt() and 0xff)
        else 0
    }

    private fun length8Size(data: ByteArray, offset: Int): Int =
        if ((data[offset].toInt() and 0xff) and 0x80 == 0) 1 else 2

    private fun align4(value: Int): Int = (value + 3) and -4

    private fun readU16(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(little).short.toInt() and 0xffff

    private fun readU32(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 4).order(little).int.toLong() and 0xffffffffL

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(data, offset, 2).order(little).putShort(value.toShort())
    }

    private fun putU32(data: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(data, offset, 4).order(little).putInt(value.toInt())
    }
}
