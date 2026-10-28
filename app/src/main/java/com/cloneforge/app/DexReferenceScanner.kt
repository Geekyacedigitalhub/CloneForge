package com.cloneforge.app

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class DexPackageReference(
    val dexName: String,
    val count: Int
)

data class DexReferenceScan(
    val references: List<DexPackageReference>
) {
    val totalReferences: Int get() = references.sumOf { it.count }
}

class DexReferenceScanner {
    fun scan(apk: File, sourcePackage: String): DexReferenceScan {
        require(sourcePackage.isNotBlank()) { "Source package is empty." }

        val sourcePath = sourcePackage.replace('.', '/')
        val descriptor = ("L$sourcePath;").toByteArray(Charsets.US_ASCII)
        val nestedPrefix = ("L$sourcePath/").toByteArray(Charsets.US_ASCII)
        val buffer = ByteArray(64 * 1024)

        ZipFile(apk).use { zip ->
            val results = zip.entries().asSequence()
                .filter { isDexEntry(it.name) }
                .mapNotNull { entry ->
                    val count = zip.getInputStream(entry).use { stream ->
                        countTwoNeedlesInStream(stream, descriptor, nestedPrefix, buffer)
                    }
                    if (count == 0) null else DexPackageReference(entry.name, count)
                }
                .toList()

            return DexReferenceScan(results)
        }
    }

    private fun isDexEntry(name: String): Boolean =
        name == "classes.dex" ||
            Regex("classes([2-9]|[1-9][0-9]+)\\.dex").matches(name)

    private fun countTwoNeedlesInStream(
        input: InputStream,
        needleA: ByteArray,
        needleB: ByteArray,
        buffer: ByteArray
    ): Int {
        val maxLen = maxOf(needleA.size, needleB.size)
        if (maxLen == 0 || buffer.size < maxLen * 2) return 0
        var count = 0
        var carry = 0
        while (true) {
            val read = input.read(buffer, carry, buffer.size - carry)
            if (read <= 0) break
            val limit = carry + read
            count += countMatches(buffer, limit, needleA)
            count += countMatches(buffer, limit, needleB)
            val nextCarry = (maxLen - 1).coerceAtMost(limit)
            if (nextCarry > 0) {
                System.arraycopy(buffer, limit - nextCarry, buffer, 0, nextCarry)
            }
            carry = nextCarry
        }
        return count
    }

    private fun countMatches(buffer: ByteArray, limit: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || limit < needle.size) return 0
        var count = 0
        var index = 0
        while (index <= limit - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (buffer[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                count++
                index += needle.size
            } else {
                index++
            }
        }
        return count
    }
}
