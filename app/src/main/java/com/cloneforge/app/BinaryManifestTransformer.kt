package com.cloneforge.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ManifestRewriteResult(
    val data: ByteArray,
    val packageChanged: Boolean,
    val labelChanged: Boolean
)

class BinaryManifestTransformer {

    fun rewrite(
        manifest: ByteArray,
        sourcePackage: String,
        clonePackage: String,
        cloneLabel: String,
        versionName: String? = null,
        versionCode: Int? = null
    ): Result<ManifestRewriteResult> {
        return runCatching {
            require(sourcePackage.isNotBlank()) { "Source package is empty." }
            require(clonePackage.isNotBlank()) { "Clone package is empty." }
            require(cloneLabel.isNotBlank()) { "Clone label is empty." }

            val editor = BinaryXmlEditor(manifest)
            editor.replaceManifestPackage(sourcePackage, clonePackage)
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
}

private class BinaryXmlEditor(private val original: ByteArray) {

    private val little = ByteOrder.LITTLE_ENDIAN
    private val strings = mutableListOf<String>()
    private val stringIndexes = mutableMapOf<String, Int>()
    private var stringPoolChunk: Chunk? = null
    private val replacements = mutableMapOf<Int, Int>()
    private val intReplacements = mutableMapOf<Int, Int>()
    private val booleanReplacements = mutableMapOf<Int, Boolean>()
    private var packageReplacement = false
    private var labelReplacement = false

    data class Chunk(val offset: Int, val type: Int, val headerSize: Int, val size: Int)

    fun replaceManifestPackage(source: String, target: String) {
        val index = findString(source)
        require(index >= 0) { "Source package was not found in the manifest string pool." }

        val sourcePrefix = "$source."
        val targetPrefix = "$target."
        for (i in strings.indices) {
            val current = strings[i]
            if (current != target && !current.startsWith(targetPrefix) && current.startsWith(sourcePrefix)) {
                val updated = targetPrefix + current.removePrefix(sourcePrefix)
                strings[i] = updated
                stringIndexes[updated] = i
                packageReplacement = true
            }
        }

        strings[index] = target
        stringIndexes.remove(source)
        stringIndexes[target] = index

        val targetIndex = indexFor(target)

        findStartTagAttribute("manifest", "package")?.let {
            replacements[it] = targetIndex
            packageReplacement = true
        } ?: error("Manifest package attribute was not found.")
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
        findStartTagAttribute("manifest", "isSplitRequired")?.let {
            booleanReplacements[it] = false
        }
        findStartTagAttribute("application", "isSplitRequired")?.let {
            booleanReplacements[it] = false
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
            labelChanged = labelReplacement
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

            if (type == 0x0001) {
                stringPoolChunk = Chunk(offset, type, headerSize, size)
                readStringPool(offset, headerSize)
                return
            }

            offset += size
        }

        error("Android string pool was not found.")
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

    private fun findStartTagAttribute(tag: String, attribute: String): Int? {
        ensureStringPool()

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
                        if (strings.getOrNull(attrNameIndex) == attribute) {
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
