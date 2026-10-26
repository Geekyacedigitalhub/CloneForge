package com.cloneforge.app

import java.io.File
import java.util.zip.ZipFile

data class ClonePreflight(
    val inspection: ApkInspection,
    val compatibility: CloneCompatibility,
    val referenceScan: ApkReferenceScan,
    val dexReferenceScan: DexReferenceScan,
    val manifestInfo: BinaryManifestInfo? = null
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
        isSplitInstall: Boolean = false
    ): Result<ClonePreflight> = runCatching {
        val inspection = inspector.inspect(sourceApk)
        val referenceScan = referenceScanner.scan(sourceApk, sourcePackage)
        val dexReferenceScan = dexReferenceScanner.scan(sourceApk, sourcePackage)
        val split = isSplitInstall || hasSplitConfiguration(sourceApk)
        val manifestInfo = readManifestInfo(sourceApk)
        ClonePreflight(
            inspection,
            CloneCompatibilityChecker.check(inspection, split, referenceScan.totalReferences + dexReferenceScan.totalReferences),
            referenceScan,
            dexReferenceScan,
            manifestInfo
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
