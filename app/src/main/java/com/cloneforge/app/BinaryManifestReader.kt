package com.cloneforge.app

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BinaryManifestInfo(
    val packageName: String?,
    val applicationLabel: String?,
    val rawSize: Int
)

class BinaryManifestReader {

    companion object {
        private const val RES_XML_TYPE = 0x0003
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val START_TAG = 0x0102

        private fun u16(b: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(b, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

        private fun u32(b: ByteArray, offset: Int): Long =
            ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }

    fun read(data: ByteArray): BinaryManifestInfo {
        require(data.size >= 8) { "Manifest is too small." }

        val type = u16(data, 0)
        require(type == RES_XML_TYPE) { "Not an Android binary XML document." }

        val stringPool = StringPool.read(data)
        var offset = u16(data, 2)

        var packageName: String? = null
        var applicationLabel: String? = null

        while (offset + 8 <= data.size) {
            val chunkType = u16(data, offset)
            val headerSize = u16(data, offset + 2)
            val chunkSize = u32(data, offset + 4).toInt()

            if (chunkSize < headerSize || chunkSize <= 0 || offset + chunkSize > data.size) break

            if (chunkType == START_TAG && offset + 36 <= data.size) {
                val attributeStart = u16(data, offset + 24)
                val attributeSize = u16(data, offset + 26)
                val attributeCount = u16(data, offset + 28)

                var attrOffset = offset + attributeStart
                repeat(attributeCount) {
                    if (attrOffset + attributeSize > offset + chunkSize || attributeSize < 20) return@repeat

                    val namespace = u32(data, attrOffset).toInt()
                    val nameIndex = u32(data, attrOffset + 4).toInt()
                    val valueIndex = u32(data, attrOffset + 8).toInt()
                    val dataType = data[attrOffset + 15].toInt() and 0xff
                    val dataValue = u32(data, attrOffset + 16).toInt()

                    val name = stringPool.get(nameIndex)
                    val value = when (dataType) {
                        0x03 -> stringPool.get(valueIndex)
                        else -> dataValue.toString()
                    }

                    if (namespace == -1 || namespace == 0xffffffff) {
                        if (name == "package") packageName = value
                        if (name == "label") applicationLabel = value
                    }

                    attrOffset += attributeSize
                }
            }

            offset += chunkSize
        }

        return BinaryManifestInfo(packageName, applicationLabel, data.size)
    }
}

private class StringPool(
    private val strings: List<String>
) {
    fun get(index: Int): String? =
        if (index in strings.indices) strings[index] else null

    companion object {
        fun read(data: ByteArray): StringPool {
            var offset = u16(data, 2)
            while (offset + 8 <= data.size) {
                val type = u16(data, offset)
                val headerSize = u16(data, offset + 2)
                val chunkSize = u32(data, offset + 4).toInt()

                if (chunkSize <= 0 || offset + chunkSize > data.size) break

                if (type == 0x0001) {
                    if (offset + 28 > data.size) break

                    val stringCount = u32(data, offset + 8).toInt()
                    val flags = u32(data, offset + 16).toInt()
                    val stringsStart = u32(data, offset + 20).toInt()

                    val offsetsStart = offset + headerSize
                    val stringsBase = offset + stringsStart
                    val utf8 = (flags and 0x100) != 0
                    val strings = ArrayList<String>(stringCount)

                    repeat(stringCount) { index ->
                        val off = u32(data, offsetsStart + index * 4).toInt()
                        strings.add(readString(data, stringsBase + off, utf8))
                    }

                    return StringPool(strings)
                }

                offset += chunkSize
            }

            return StringPool(emptyList())
        }

        private fun readString(data: ByteArray, start: Int, utf8: Boolean): String {
            if (start < 0 || start >= data.size) return ""

            return if (utf8) {
                var p = start
                val first = data[p++].toInt() and 0xff
                if (first and 0x80 != 0) p++
                val second = data[p++].toInt() and 0xff
                if (second and 0x80 != 0) p++
                val length = second
                if (p + length > data.size) return ""
                String(data, p, length, Charsets.UTF_8)
            } else {
                var p = start
                val first = u16(data, p)
                p += if (first and 0x8000 != 0) 4 else 2
                val length = first and 0x7fff
                if (p + length * 2 > data.size) return ""
                String(data, p, length * 2, Charsets.UTF_16LE)
            }
        }

        private fun u16(b: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(b, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

        private fun u32(b: ByteArray, offset: Int): Long =
            ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }
}
