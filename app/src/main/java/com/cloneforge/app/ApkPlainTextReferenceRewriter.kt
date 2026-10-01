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
                if (!isSafePlainTextTarget(path)) return@forEach

                val count = rewriter.rewritePlainTextFile(file, sourcePackage, clonePackage)
                if (count > 0) {
                    changed += path
                    replacements += count
                }
            }

        return PackageRewriteResult(changed, replacements)
    }

    private fun isSafePlainTextTarget(path: String): Boolean {
        return path.startsWith("assets/", ignoreCase = true) ||
            path.startsWith("res/raw/", ignoreCase = true) ||
            path.endsWith(".txt", ignoreCase = true) ||
            path.endsWith(".json", ignoreCase = true) ||
            path.endsWith(".xml", ignoreCase = true) && !path.equals("AndroidManifest.xml", ignoreCase = true) ||
            path.endsWith(".properties", ignoreCase = true) ||
            path.endsWith(".ini", ignoreCase = true)
    }
}
