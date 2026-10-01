package com.cloneforge.app

data class CloneCompatibility(
    val supported: Boolean,
    val title: String,
    val issues: List<String>,
    val warnings: List<String>
)

object CloneCompatibilityChecker {
    fun check(inspection: ApkInspection, isSplitApk: Boolean = false): CloneCompatibility {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!inspection.hasManifest) issues += "AndroidManifest.xml is missing."
        if (!inspection.hasDex) issues += "No DEX bytecode was found."
        if (!inspection.hasResources) {
            warnings += "resources.arsc is missing. This may be resource-light, but resource-dependent apps can fail."
        }
        if (inspection.nativeLibraryCount > 0) {
            warnings += "The APK contains ${inspection.nativeLibraryCount} native libraries. Native code may contain hardcoded package or signature checks."
        }
        if (inspection.dexCount > 1) {
            warnings += "The APK contains ${inspection.dexCount} DEX files. Multi-DEX apps may contain hardcoded package references."
        }
        if (isSplitApk) issues += "This app uses split APKs. CloneForge currently requires a single base APK."

        return CloneCompatibility(
            supported = issues.isEmpty(),
            title = if (issues.isEmpty()) "Ready for cloning" else "Special handling required",
            issues = issues,
            warnings = warnings
        )
    }
}
