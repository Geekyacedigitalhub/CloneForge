package com.cloneforge.app

import java.io.File
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
        val descriptor = "L$sourcePath;"
        val nestedPrefix = "L$sourcePath/"

        ZipFile(apk).use { zip ->
            val results = zip.entries().asSequence()
                .filter { isDexEntry(it.name) }
                .mapNotNull { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val count = countAscii(bytes, descriptor) + countAscii(bytes, nestedPrefix)
                    if (count == 0) null else DexPackageReference(entry.name, count)
                }
                .toList()

            return DexReferenceScan(results)
        }
    }

    private fun isDexEntry(name: String): Boolean =
        name == "classes.dex" ||
            Regex("classes[2-9][0-9]*\\.dex").matches(name)

    private fun countAscii(data: ByteArray, value: String): Int {
        val needle = value.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || data.size < needle.size) return 0

        var count = 0
        var index = 0
        while (index <= data.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (data[index + offset] != needle[offset]) {
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
