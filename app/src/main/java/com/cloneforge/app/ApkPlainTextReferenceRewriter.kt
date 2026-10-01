package com.cloneforge.app

import java.io.File

class ApkPlainTextReferenceRewriter(
    private val rewriter: PackageReferenceRewriter = PackageReferenceRewriter()
) {
    fun rewrite(unpackedDir: File, sourcePackage: String, clonePackage: String): PackageRewriteResult {
        val changed = mutableListOf<String>()
        var replacements = 0

        unpackedDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val path = file.relativeTo(unpackedDir).invariantSeparatorsPath
                if (!isSafePlainTextTarget(path) || !isLikelyPlainText(file)) return@forEach

                val count = rewriter.rewritePlainTextFile(file, sourcePackage, clonePackage)
                if (count > 0) {
                    changed += path
                    replacements += count
                }
            }

        return PackageRewriteResult(changed, replacements)
    }

    private fun isSafePlainTextTarget(path: String): Boolean {
        if (path.equals("AndroidManifest.xml", ignoreCase = true)) return false

        val lower = path.lowercase()
        val textualExtension = lower.endsWith(".txt") ||
            lower.endsWith(".json") ||
            lower.endsWith(".xml") ||
            lower.endsWith(".properties") ||
            lower.endsWith(".ini") ||
            lower.endsWith(".yaml") ||
            lower.endsWith(".yml") ||
            lower.endsWith(".csv") ||
            lower.endsWith(".html") ||
            lower.endsWith(".js") ||
            lower.endsWith(".ts")

        // assets/ and res/raw/ can contain arbitrary binary payloads. Only
        // rewrite files with an explicitly textual extension so we do not
        // accidentally modify images, databases, fonts, audio, or opaque resources.
        return textualExtension
    }
    private fun isLikelyPlainText(file: File): Boolean {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(4096)
            val read = input.read(buffer)
            if (read <= 0) return true
            buffer.copyOf(read)
        }

        // Android binary XML starts with RES_XML_TYPE (0x0003) and should
        // never be passed through the plain-text package rewriter.
        if (bytes.size >= 2 && bytes[0] == 0x03.toByte() && bytes[1] == 0x00.toByte()) {
            return false
        }

        var suspiciousControls = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value == 0) return false
            if (value < 0x09 || (value in 0x0E..0x1F)) {
                suspiciousControls++
            }
        }

        return suspiciousControls <= (bytes.size / 100).coerceAtLeast(2)
    }
}
