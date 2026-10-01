package com.cloneforge.app

import java.io.File

data class ManifestTransformRequest(
    val sourceApk: File,
    val sourcePackage: String,
    val clonePackage: String,
    val cloneLabel: String
)

data class ManifestTransformResult(
    val sourcePackage: String,
    val clonePackage: String,
    val cloneLabel: String,
    val manifestFound: Boolean,
    val transformationReady: Boolean,
    val message: String
)

/**
 * Coordinates manifest transformation without pretending that a binary
 * AndroidManifest.xml can be safely edited as plain UTF-8 XML.
 *
 * The binary XML rewrite implementation will be supplied by the APK rebuild
 * layer. Keeping this contract separate lets the rest of CloneForge validate
 * a clone plan before modifying the source archive.
 */
class ManifestTransformer {

    fun validate(request: ManifestTransformRequest): Result<ManifestTransformResult> {
        if (!request.sourceApk.exists() || !request.sourceApk.isFile) {
            return Result.failure(
                IllegalArgumentException("Source APK does not exist.")
            )
        }

        if (!request.sourcePackage.contains(".")) {
            return Result.failure(
                IllegalArgumentException("Source package is invalid.")
            )
        }

        if (!request.clonePackage.contains(".")) {
            return Result.failure(
                IllegalArgumentException("Clone package is invalid.")
            )
        }

        if (request.cloneLabel.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Clone label cannot be empty.")
            )
        }

        val inspection = ApkInspector().inspect(request.sourceApk)

        if (!inspection.hasManifest) {
            return Result.failure(
                IllegalStateException("APK has no AndroidManifest.xml.")
            )
        }

        return Result.success(
            ManifestTransformResult(
                sourcePackage = request.sourcePackage,
                clonePackage = request.clonePackage,
                cloneLabel = request.cloneLabel,
                manifestFound = true,
                transformationReady = true,
                message = "Manifest located and clone transformation request validated."
            )
        )
    }
}
