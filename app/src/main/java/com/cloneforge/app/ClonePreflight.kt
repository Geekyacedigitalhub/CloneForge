package com.cloneforge.app

import java.io.File
import java.util.zip.ZipFile

data class ClonePreflight(
    val inspection: ApkInspection,
    val compatibility: CloneCompatibility
)

class ClonePreflightService(private val inspector: ApkInspector = ApkInspector()) {
    fun inspect(sourceApk: File): Result<ClonePreflight> = runCatching {
        val inspection = inspector.inspect(sourceApk)
        val split = hasSplitConfiguration(sourceApk)
        ClonePreflight(
            inspection,
            CloneCompatibilityChecker.check(inspection, split)
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
