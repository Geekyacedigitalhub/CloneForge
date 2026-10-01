package com.cloneforge.app

import java.io.File
import java.nio.charset.Charset

data class PackageRewriteResult(
    val changedEntries: List<String>,
    val replacements: Int
)

class PackageReferenceRewriter {
    fun rewritePlainTextFile(
        file: File,
        sourcePackage: String,
        clonePackage: String
    ): Int {
        require(sourcePackage.isNotBlank()) { "Source package is empty." }
        require(clonePackage.isNotBlank()) { "Clone package is empty." }

        val bytes = file.readBytes()
        val charset = Charset.forName("ISO-8859-1")
        val original = bytes.toString(charset)
        val replacements = countOccurrences(original, sourcePackage)
        if (replacements == 0) return 0

        file.writeText(original.replace(sourcePackage, clonePackage), charset)
        return replacements
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
