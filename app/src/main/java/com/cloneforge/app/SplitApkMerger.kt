package com.cloneforge.app

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * and dynamic feature splits from `ApplicationInfo.splitSourceDirs`) into a single
 * standalone base APK containing native libraries, density resources, assets,
 * merged `0x7f` `resources.arsc` configuration chunks, and secondary DEX files.
 */
class SplitApkMerger(
    private val arscSplitMerger: ArscSplitMerger = ArscSplitMerger()
) {

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
            .sortedWith(
                compareBy<File> { file ->
                    val name = file.name.lowercase()
                    when {
                        name.startsWith("split_config.") || name.startsWith("config.") -> 0
                        !name.contains(".config.") -> 1
                        else -> 2
                    }
                }.thenBy { it.name }
            )

        if (validSplits.isEmpty()) {
            baseApk.inputStream().use { input ->
                outputApk.outputStream().use { output -> input.copyTo(output) }
            }
            return@runCatching SplitMergeResult(outputApk, splitsMerged = 0, entriesAdded = 0)
        }

        val baseSplitArscs = mutableListOf<ByteArray>()
        for (splitFile in validSplits) {
            runCatching {
                ZipFile(splitFile).use { splitZip ->
                    if (!hasNonBaseArscPackageId(splitZip)) {
                        val arscEntry = splitZip.getEntry("resources.arsc")
                        if (arscEntry != null) {
                            baseSplitArscs.add(
                                splitZip.getInputStream(arscEntry).use { it.readBytes() }
                            )
                        }
                    }
                }
            }
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
                    if (safeName == "resources.arsc" && baseSplitArscs.isNotEmpty()) {
                        val baseArsc = baseZip.getInputStream(entry).use { it.readBytes() }
                        val mergedArsc = arscSplitMerger.mergeBaseSplits(baseArsc, baseSplitArscs)
                        outZip.write(mergedArsc)
                    } else {
                        baseZip.getInputStream(entry).use { input -> input.copyTo(outZip) }
                    }
                    outZip.closeEntry()
                }
            }

            for (splitFile in validSplits) {
                ZipFile(splitFile).use { splitZip ->
                    val hasNonBaseResourcePackage = hasNonBaseArscPackageId(splitZip)
                    val entries = splitZip.entries().asSequence().toList()
                    for (entry in entries) {
                        if (entry.isDirectory) continue
                        val safeName = sanitizeEntryPath(entry.name) ?: continue
                        if (isSignatureEntry(safeName)) continue
                        if (safeName == "AndroidManifest.xml") continue
                        if (safeName == "resources.arsc" && "resources.arsc" in seenEntries) continue
                        if (hasNonBaseResourcePackage && safeName.startsWith("res/")) continue

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

    /**
     * Checks if a split APK contains its own `resources.arsc` whose `ResTable_package`
     * ID is not `0x7f` (e.g. `0x7e`, `0x7d` for dynamic feature splits). Since a standalone
     * APK can only contain a single `resources.arsc` (`0x7f`), `res/` XML files compiled
     * against a non-`0x7f` package ID must not overwrite or pollute `base.apk`'s `res/`.
     */
    private fun hasNonBaseArscPackageId(zip: ZipFile): Boolean = runCatching {
        val arscEntry = zip.getEntry("resources.arsc") ?: return false
        val headerBytes = zip.getInputStream(arscEntry).use { input ->
            val buf = ByteArray(4096)
            var total = 0
            while (total < buf.size) {
                val read = input.read(buf, total, buf.size - total)
                if (read <= 0) break
                total += read
            }
            buf.copyOf(total)
        }
        if (headerBytes.size < 24) return false
        val little = ByteOrder.LITTLE_ENDIAN
        fun u16(off: Int): Int = ByteBuffer.wrap(headerBytes, off, 2).order(little).short.toInt() and 0xffff
        fun u32(off: Int): Int = ByteBuffer.wrap(headerBytes, off, 4).order(little).int

        val rootType = u16(0)
        val rootHeaderSize = u16(2)
        if (rootType != 0x0002 || rootHeaderSize < 12) return false

        var offset = rootHeaderSize
        while (offset + 12 <= headerBytes.size) {
            val chunkType = u16(offset)
            val headerSize = u16(offset + 2)
            val chunkSize = u32(offset + 4)
            if (chunkSize <= 0 || chunkSize < headerSize) break
            if (chunkType == 0x0200 && offset + 12 <= headerBytes.size) {
                val pkgId = u32(offset + 8)
                return pkgId in 1..0x7e || pkgId > 0x7f
            }
            if (offset + chunkSize > headerBytes.size) break
            offset += chunkSize
        }
        false
    }.getOrDefault(false)

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
