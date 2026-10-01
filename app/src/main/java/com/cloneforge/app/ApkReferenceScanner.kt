package com.cloneforge.app

import java.io.File
import java.nio.charset.Charset
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
    fun scan(apk: File, sourcePackage: String): ApkReferenceScan {
        require(sourcePackage.isNotBlank()) { "Source package cannot be empty." }
        ZipFile(apk).use { zip ->
            var dex = 0
            var resources = 0
            var assets = 0
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val text = zip.getInputStream(entry).use { it.readBytes().toString(Charset.forName("ISO-8859-1")) }
                val count = countOccurrences(text, sourcePackage)
                when {
                    entry.name.matches(Regex("classes([2-9][0-9]*)?\\.dex")) -> dex += count
                    entry.name == "resources.arsc" -> resources += count
                    entry.name.startsWith("assets/") -> assets += count
                }
            }
            return ApkReferenceScan(sourcePackage, dex, resources, assets)
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
