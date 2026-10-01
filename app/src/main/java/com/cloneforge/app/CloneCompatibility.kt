package com.cloneforge.app

data class CloneCompatibility(
    val supported: Boolean,
    val title: String,
    val issues: List<String>,
    val warnings: List<String>,
    val packageReferenceCount: Int = 0
) {
    val hasWarnings: Boolean
        get() = warnings.isNotEmpty()
}

object CloneCompatibilityChecker {
    fun check(
        inspection: ApkInspection,
        isSplitApk: Boolean = false,
        packageReferenceCount: Int = 0
    ): CloneCompatibility {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!inspection.hasManifest) {
            issues += "AndroidManifest.xml is missing."
        }

        if (!inspection.hasDex) {
            issues += "No DEX bytecode was found."
        }

        if (isSplitApk) {
            issues += "This app uses split APKs. CloneForge currently requires a single base APK."
        }

        warnings += "The clone is signed with CloneForge’s on-device signing key, not the source app’s original certificate."
        warnings += "Apps that verify their original signature, use shared signing identities, Play Integrity, or signature-based licensing may not work after cloning."

        if (!inspection.hasResources) {
            warnings += "resources.arsc is missing. The APK may be resource-light, but resource-dependent apps can fail."
        } else {
            warnings += "resources.arsc is preserved without binary package rewriting. Apps with package-specific resource metadata may need additional handling."
        }

        if (inspection.nativeLibraryCount > 0) {
            warnings += "Found ${inspection.nativeLibraryCount} native libraries. CloneForge preserves native code as-is; hardcoded package, path, or signature checks may remain."
        }

        if (inspection.dexCount > 1) {
            warnings += "Found ${inspection.dexCount} DEX files. Multi-DEX apps can contain additional package references that should be verified."
        }

        if (packageReferenceCount > 0) {
            warnings += "Detected $packageReferenceCount raw references to the source package outside the structured DEX scan. These may require rewriting."
        }

        val supported = issues.isEmpty()

        return CloneCompatibility(
            supported = supported,
            title = if (supported) {
                if (warnings.isEmpty()) "Ready for cloning" else "Ready with warnings"
            } else {
                "Special handling required"
            },
            issues = issues,
            warnings = warnings,
            packageReferenceCount = packageReferenceCount
        )
    }
}
