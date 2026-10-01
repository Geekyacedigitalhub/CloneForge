package com.cloneforge.app

data class CloneCompatibility(
    val supported: Boolean,
    val title: String,
    val issues: List<String>,
    val warnings: List<String>,
    val packageReferenceCount: Int = 0
)

object CloneCompatibilityChecker {
    fun check(inspection: ApkInspection, isSplitApk: Boolean = false, packageReferenceCount: Int = 0): CloneCompatibility {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!inspection.hasManifest) issues += "AndroidManifest.xml is missing."
        if (!inspection.hasDex) issues += "No DEX bytecode was found."
        if (!inspection.hasResources) {
            warnings += "resources.arsc is missing. This may be resource-light, but resource-dependent apps can fail."
        } else {
            warnings += "resources.arsc is preserved without binary package rewriting. Apps with package-specific resource metadata may need additional handling."
        }
        if (inspection.nativeLibraryCount > 0) {
            warnings += "The APK contains ${inspection.nativeLibraryCount} native libraries. Native code is preserved as-is and may contain hardcoded package, path, or signature checks."
            warnings += "Native libraries are not rewritten by CloneForge. Apps that require package-specific native configuration may not work after cloning."
        }
        if (inspection.dexCount > 1) {
            warnings += "The APK contains ${inspection.dexCount} DEX files. Multi-DEX apps may contain hardcoded package references."
        }
        if (packageReferenceCount > 0) {
            warnings += "Detected $packageReferenceCount raw references to the source package. These may require rewriting."
        }
        if (isSplitApk) issues += "This app uses split APKs. CloneForge currently requires a single base APK."

        return CloneCompatibility(
            supported = issues.isEmpty(),
            title = if (issues.isEmpty()) "Ready for cloning" else "Special handling required",
            issues = issues,
            warnings = warnings,
            packageReferenceCount = packageReferenceCount
        )
    }
}
