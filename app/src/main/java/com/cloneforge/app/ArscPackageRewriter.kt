package com.cloneforge.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ArscRewriteResult(
    val data: ByteArray,
    val packagesRewritten: Int
)

/**
 * Rewrites the fixed-width 128-character UTF-16LE package name field inside
 * Android's binary `resources.arsc` (`RES_TABLE_PACKAGE_TYPE` = `0x0200`) chunks.
 *
 * Updating the resource table package name allows runtime calls such as
 * `Resources.getIdentifier(name, defType, context.packageName)` (commonly used
 * for notification small icons, custom notification layouts, and dynamic assets)
 * to resolve against the cloned package name.
 */
class ArscPackageRewriter {

    companion object {
        private const val RES_TABLE_TYPE = 0x0002
        private const val RES_TABLE_PACKAGE_TYPE = 0x0200
        private const val PACKAGE_NAME_OFFSET = 12
        private const val PACKAGE_NAME_CHARS = 128
        private const val PACKAGE_NAME_BYTES = PACKAGE_NAME_CHARS * 2
        private const val MIN_PACKAGE_HEADER_SIZE = PACKAGE_NAME_OFFSET + PACKAGE_NAME_BYTES
    }

    fun rewrite(
        arscBytes: ByteArray,
        sourcePackage: String,
        clonePackage: String
    ): ArscRewriteResult {
        if (arscBytes.size < 12 ||
            sourcePackage.isBlank() ||
            clonePackage.isBlank() ||
            clonePackage.length >= PACKAGE_NAME_CHARS
        ) {
            return ArscRewriteResult(arscBytes, 0)
        }

        val rootType = u16(arscBytes, 0)
        val rootHeaderSize = u16(arscBytes, 2)
        val rootSize = u32(arscBytes, 4).toInt()
        if (rootType != RES_TABLE_TYPE || rootHeaderSize < 12 || rootSize > arscBytes.size) {
            return ArscRewriteResult(arscBytes, 0)
        }

        val result = arscBytes.copyOf()
        var offset = rootHeaderSize
        var rewritten = 0

        while (offset + 8 <= result.size) {
            val chunkType = u16(result, offset)
            val headerSize = u16(result, offset + 2)
            val chunkSize = u32(result, offset + 4).toInt()

            if (chunkSize <= 0 || chunkSize < headerSize || offset + chunkSize > result.size) {
                break
            }

            if (chunkType == RES_TABLE_PACKAGE_TYPE &&
                headerSize >= MIN_PACKAGE_HEADER_SIZE &&
                offset + MIN_PACKAGE_HEADER_SIZE <= result.size
            ) {
                val currentPackage = readPackageName(result, offset + PACKAGE_NAME_OFFSET)
                if (currentPackage == sourcePackage) {
                    writePackageName(result, offset + PACKAGE_NAME_OFFSET, clonePackage)
                    rewritten++
                }
            }

            offset += chunkSize
        }

        return ArscRewriteResult(result, rewritten)
    }

    private fun readPackageName(data: ByteArray, start: Int): String {
        val chars = CharArray(PACKAGE_NAME_CHARS)
        var length = 0
        while (length < PACKAGE_NAME_CHARS) {
            val codeUnit = u16(data, start + length * 2)
            if (codeUnit == 0) break
            chars[length] = codeUnit.toChar()
            length++
        }
        return String(chars, 0, length)
    }

    private fun writePackageName(data: ByteArray, start: Int, packageName: String) {
        for (i in 0 until PACKAGE_NAME_BYTES) {
            data[start + i] = 0
        }
        val chars = packageName.toCharArray()
        val limit = chars.size.coerceAtMost(PACKAGE_NAME_CHARS - 1)
        for (i in 0 until limit) {
            putU16(data, start + i * 2, chars[i].code)
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun u32(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }
}
