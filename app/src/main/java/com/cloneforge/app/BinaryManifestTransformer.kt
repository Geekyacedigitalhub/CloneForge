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
        cloneLabel: String
    ): Result<ManifestRewriteResult> {
        return runCatching {
            require(sourcePackage.isNotBlank()) { "Source package is empty." }
            require(clonePackage.isNotBlank()) { "Clone package is empty." }
            require(cloneLabel.isNotBlank()) { "Clone label is empty." }

            val editor = BinaryXmlEditor(manifest)
            editor.replaceManifestPackage(sourcePackage, clonePackage)
            editor.replaceApplicationLabel(cloneLabel)

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
    private var packageReplacement = false
    private var labelReplacement = false

    data class Chunk(val offset: Int, val type: Int, val headerSize: Int, val size: Int)

    fun replaceManifestPackage(source: String, target: String) {
        val index = findString(source)
        require(index >= 0) { "Source package was not found in the manifest string pool." }

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

    fun build(): ManifestRewriteResult {
        val pool = stringPoolChunk ?: error("Android string pool not found.")
        val newPool = buildStringPool(pool)

        val before = original.copyOfRange(0, pool.offset)
        val after = original.copyOfRange(pool.offset + pool.size, original.size)

        val result = ByteArray(before.size + newPool.size + after.size)
        System.arraycopy(before, 0, result, 0, before.size)
        System.arraycopy(newPool, 0, result, before.size, newPool.size)
        System.arraycopy(after, 0, result, before.size + newPool.size, after.size)

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

                    var attr = offset + attributeStart

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
        putU32(out, 16, flags.toLong())
        putU32(out, 20, stringsStart.toLong())
        putU32(out, 24, 0)

        offsets.forEachIndexed { index, value ->
            putU32(out, headerSize + index * 4, value.toLong())
        }

        encoded.forEachIndexed { index, bytes ->
            System.arraycopy(bytes, 0, out, stringsStart + offsets[index], bytes, 0, bytes.size)
        }

        return out
    }

    private fun encodeString(value: String, utf8: Boolean): ByteArray {
        return if (utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val len = bytes.size
            val prefix = if (len < 0x80) byteArrayOf(len.toByte()) else byteArrayOf(
                ((len shr 7) or 0x80).toByte(),
                (len and 0x7f).toByte()
            )

            val chars = value.length
            val charPrefix = if (chars < 0x80) byteArrayOf(chars.toByte()) else byteArrayOf(
                ((chars shr 7) or 0x80).toByte(),
                (chars and 0x7f).toByte()
            )

            prefix + charPrefix + bytes + byteArrayOf(0)
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
            val byteLength = readLength8(data, p)
            p += length8Size(data, p)
            val charLength = readLength8(data, p)
            p += length8Size(data, p)
            if (p + byteLength > data.size) return ""
            String(data, p, byteLength, Charsets.UTF_8)
        } else {
            val length = readU16(data, start)
            val p = start + 2
            if (p + length * 2 > data.size) return ""
            String(data, p, length * 2, Charsets.UTF_16LE)
        }
    }

    private fun readLength8(data: ByteArray, offset: Int): Int {
        val first = data[offset].toInt() and 0xff
        return if (first and 0x80 == 0) first
        else ((first and 0x7f) shl 7) or (data[offset + 1].toInt() and 0xff)
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
