package com.cloneforge.app

import java.io.File
import java.util.zip.ZipFile

data class ClonePreflight(
    val inspection: ApkInspection,
    val compatibility: CloneCompatibility,
    val referenceScan: ApkReferenceScan,
    val dexReferenceScan: DexReferenceScan
)

class ClonePreflightService(private val inspector: ApkInspector = ApkInspector()) {
    fun inspect(sourceApk: File, sourcePackage: String): Result<ClonePreflight> = runCatching {
        val inspection = inspector.inspect(sourceApk)
        val referenceScan = referenceScanner.scan(sourceApk, sourcePackage)
        val dexReferenceScan = dexReferenceScanner.scan(sourceApk, sourcePackage)
        val split = hasSplitConfiguration(sourceApk)
        ClonePreflight(
            inspection,
            CloneCompatibilityChecker.check(inspection, split, referenceScan.totalReferences + dexReferenceScan.totalReferences),
            referenceScan,
            dexReferenceScan
        )
    }

    private fun hasSplitConfiguration(apk: File): Boolean {
        ZipFile(apk).use { zip ->
            return zip.entries().asSequence().any { entry ->
                entry.name.startsWith("split_config.", ignoreCase = true) ||
                    entry.name.startsWith("split_", ignoreCase = true)
            }
        }
    }
}
