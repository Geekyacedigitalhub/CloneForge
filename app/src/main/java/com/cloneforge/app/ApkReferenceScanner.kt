package com.cloneforge.app

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class ApkReferenceScan(
    val sourcePackage: String,
    val dexReferences: Int,
    val resourceReferences: Int,
    val assetReferences: Int
) {
    val totalReferences: Int get() = dexReferences + resourceReferences + assetReferences
}

class ApkReferenceScanner {
    private val dexRegex = Regex("classes([2-9]|[1-9][0-9]+)?\\.dex")

    fun scan(apk: File, sourcePackage: String): ApkReferenceScan {
        require(sourcePackage.isNotBlank()) { "Source package cannot be empty." }
        val needle = sourcePackage.toByteArray(Charsets.US_ASCII)
        val buffer = ByteArray(64 * 1024)
        ZipFile(apk).use { zip ->
            var dex = 0
            var resources = 0
            var assets = 0
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val name = entry.name
                val isDex = name.matches(dexRegex)
                val isResources = name == "resources.arsc"
                val isAsset = name.startsWith("assets/")
                if (!isDex && !isResources && !isAsset) return@forEach

                val count = zip.getInputStream(entry).use { stream ->
                    countOccurrencesInStream(stream, needle, buffer)
                }
                when {
                    isDex -> dex += count
                    isResources -> resources += count
                    isAsset -> assets += count
                }
            }
            return ApkReferenceScan(sourcePackage, dex, resources, assets)
        }
    }

    internal fun countOccurrencesInStream(
        input: InputStream,
        needle: ByteArray,
        buffer: ByteArray = ByteArray(64 * 1024)
    ): Int {
        if (needle.isEmpty() || buffer.size < needle.size * 2) return 0
        var count = 0
        var carry = 0
        while (true) {
            val read = input.read(buffer, carry, buffer.size - carry)
            if (read <= 0) break
            val limit = carry + read
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
            val nextCarry = (needle.size - 1).coerceAtMost(limit)
            if (nextCarry > 0) {
                System.arraycopy(buffer, limit - nextCarry, buffer, 0, nextCarry)
            }
            carry = nextCarry
        }
        return count
    }
}
