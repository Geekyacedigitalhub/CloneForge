package com.cloneforge.app

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class SplitMergeResult(
    val outputApk: File,
    val splitsMerged: Int,
    val entriesAdded: Int
)

/**
 * Merges an Android base APK with its companion split APKs (`split_config.*.apk`
 * from `ApplicationInfo.splitSourceDirs`) into a single standalone base APK
 * containing native libraries, density resources, assets, and secondary DEX files.
 */
class SplitApkMerger {

    private val dexRegex = Regex("classes([2-9]|[1-9][0-9]+)?\\.dex")

    fun merge(
        baseApk: File,
        splitApks: List<File>,
        outputApk: File
    ): Result<SplitMergeResult> = runCatching {
        require(baseApk.exists() && baseApk.isFile) { "Base APK does not exist." }
        outputApk.parentFile?.mkdirs()

        val validSplits = splitApks
            .filter { it.exists() && it.isFile && it.canonicalFile != baseApk.canonicalFile }
            .distinctBy { it.canonicalPath }

        if (validSplits.isEmpty()) {
            baseApk.inputStream().use { input ->
                outputApk.outputStream().use { output -> input.copyTo(output) }
            }
            return@runCatching SplitMergeResult(outputApk, splitsMerged = 0, entriesAdded = 0)
        }

        val seenEntries = mutableSetOf<String>()
        var maxDexIndex = 0
        var entriesAdded = 0

        ZipOutputStream(FileOutputStream(outputApk)).use { outZip ->
            ZipFile(baseApk).use { baseZip ->
                val entries = baseZip.entries().asSequence().toList()
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    val safeName = sanitizeEntryPath(entry.name) ?: continue
                    if (isSignatureEntry(safeName)) continue
                    if (!seenEntries.add(safeName)) continue

                    if (safeName.matches(dexRegex)) {
                        val idx = safeName.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1
                        if (idx > maxDexIndex) maxDexIndex = idx
                    }

                    val newEntry = ZipEntry(safeName).apply { time = 0L }
                    outZip.putNextEntry(newEntry)
                    baseZip.getInputStream(entry).use { input -> input.copyTo(outZip) }
                    outZip.closeEntry()
                }
            }

            for (splitFile in validSplits) {
                ZipFile(splitFile).use { splitZip ->
                    val entries = splitZip.entries().asSequence().toList()
                    for (entry in entries) {
                        if (entry.isDirectory) continue
                        val safeName = sanitizeEntryPath(entry.name) ?: continue
                        if (isSignatureEntry(safeName)) continue
                        if (safeName == "AndroidManifest.xml") continue
                        if (safeName == "resources.arsc" && "resources.arsc" in seenEntries) continue

                        val targetName = if (safeName.matches(dexRegex)) {
                            maxDexIndex = if (maxDexIndex < 1) 1 else maxDexIndex + 1
                            if (maxDexIndex == 1) "classes.dex" else "classes$maxDexIndex.dex"
                        } else {
                            safeName
                        }

                        if (!seenEntries.add(targetName)) continue

                        val newEntry = ZipEntry(targetName).apply { time = 0L }
                        outZip.putNextEntry(newEntry)
                        splitZip.getInputStream(entry).use { input -> input.copyTo(outZip) }
                        outZip.closeEntry()
                        entriesAdded++
                    }
                }
            }
        }

        SplitMergeResult(
            outputApk = outputApk,
            splitsMerged = validSplits.size,
            entriesAdded = entriesAdded
        )
    }

    private fun sanitizeEntryPath(name: String): String? {
        val normalized = name.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return null
        if (normalized.split('/').any { it == ".." }) return null
        return normalized
    }

    private fun isSignatureEntry(path: String): Boolean {
        if (!path.startsWith("META-INF/", ignoreCase = true)) return false
        val name = path.substringAfterLast('/').uppercase()
        return name.endsWith(".SF") ||
            name.endsWith(".RSA") ||
            name.endsWith(".DSA") ||
            name.endsWith(".EC") ||
            name == "MANIFEST.MF"
    }
}
