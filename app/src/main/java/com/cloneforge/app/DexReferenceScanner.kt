package com.cloneforge.app

import java.io.File
import java.nio.charset.Charset
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

        ZipFile(apk).use { zip ->
            val results = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes([2-9][0-9]*)?\.dex")) }
                .mapNotNull { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val text = bytes.toString(Charset.forName("ISO-8859-1"))
                    val count = countOccurrences(text, sourcePackage)
                    if (count == 0) null else DexPackageReference(entry.name, count)
                }
                .toList()

            return DexReferenceScan(results)
        }
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var index = 0
        var count = 0
        while (true) {
            index = text.indexOf(needle, index)
            if (index < 0) return count
            count++
            index += needle.length
        }
    }
}
