package com.cloneforge.app

import java.io.File
import java.util.zip.ZipFile

data class ClonePreflight(
    val inspection: ApkInspection,
    val compatibility: CloneCompatibility,
    val referenceScan: ApkReferenceScan,
    val dexReferenceScan: DexReferenceScan,
    val manifestInfo: BinaryManifestInfo? = null,
    val splitApkCount: Int = 0
)

class ClonePreflightService(
    private val inspector: ApkInspector = ApkInspector(),
    private val referenceScanner: ApkReferenceScanner = ApkReferenceScanner(),
    private val dexReferenceScanner: DexReferenceScanner = DexReferenceScanner(),
    private val manifestReader: BinaryManifestReader = BinaryManifestReader()
) {
    fun inspect(
        sourceApk: File,
        sourcePackage: String,
        isSplitInstall: Boolean = false,
        splitApks: List<File> = emptyList()
    ): Result<ClonePreflight> = runCatching {
        val baseInspection = inspector.inspect(sourceApk)
        val validSplits = splitApks.filter { it.exists() && it.isFile }
        val splitInspections = validSplits.mapNotNull { runCatching { inspector.inspect(it) }.getOrNull() }
        val inspection = if (splitInspections.isEmpty()) {
            baseInspection
        } else {
            baseInspection.copy(
                hasDex = baseInspection.hasDex || splitInspections.any { it.hasDex },
                hasResources = baseInspection.hasResources || splitInspections.any { it.hasResources },
                dexCount = baseInspection.dexCount + splitInspections.sumOf { it.dexCount },
                nativeLibraryCount = baseInspection.nativeLibraryCount + splitInspections.sumOf { it.nativeLibraryCount },
                entryCount = baseInspection.entryCount + splitInspections.sumOf { it.entryCount }
            )
        }
        val referenceScan = referenceScanner.scan(sourceApk, sourcePackage)
        val dexReferenceScan = dexReferenceScanner.scan(sourceApk, sourcePackage)
        val split = isSplitInstall || hasSplitConfiguration(sourceApk)
        val manifestInfo = readManifestInfo(sourceApk)
        ClonePreflight(
            inspection,
            CloneCompatibilityChecker.check(inspection, split, referenceScan.totalReferences + dexReferenceScan.totalReferences),
            referenceScan,
            dexReferenceScan,
            manifestInfo,
            validSplits.size
        )
    }

    private fun readManifestInfo(apk: File): BinaryManifestInfo? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return@use null
            manifestReader.read(zip.getInputStream(entry).use { it.readBytes() })
        }
    }.getOrNull()

    private fun hasSplitConfiguration(apk: File): Boolean {
        ZipFile(apk).use { zip ->
            return zip.entries().asSequence().any { entry ->
                entry.name.startsWith("split_config.", ignoreCase = true) ||
                    entry.name.startsWith("split_", ignoreCase = true)
            }
        }
    }
}
