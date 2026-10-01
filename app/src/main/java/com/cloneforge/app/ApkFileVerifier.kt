package com.cloneforge.app

import java.io.File
import java.util.zip.ZipFile

data class ApkFileVerification(
    val hasManifest: Boolean,
    val hasDex: Boolean,
    val hasResources: Boolean,
    val dexCount: Int,
    val manifestPackage: String?,
    val verified: Boolean,
    val error: String? = null
)

class ApkFileVerifier(
    private val manifestReader: BinaryManifestReader = BinaryManifestReader()
) {
    fun verify(apkFile: File): ApkFileVerification {
        if (!apkFile.exists() || !apkFile.isFile) {
            return ApkFileVerification(
                hasManifest = false,
                hasDex = false,
                hasResources = false,
                dexCount = 0,
                manifestPackage = null,
                verified = false,
                error = "APK file does not exist."
            )
        }

        return runCatching {
            ZipFile(apkFile).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                val hasManifest = manifestEntry != null
                val manifestPackage = manifestEntry?.let {
                    manifestReader.read(
                        zip.getInputStream(it).use { input -> input.readBytes() }
                    ).packageName
                }

                val dexCount = zip.entries().asSequence()
                    .count { it.name.matches(Regex("classes([2-9][0-9]*)?\\.dex")) }
                val hasDex = dexCount > 0
                val hasResources = zip.getEntry("resources.arsc") != null

                ApkFileVerification(
                    hasManifest = hasManifest,
                    hasDex = hasDex,
                    hasResources = hasResources,
                    dexCount = dexCount,
                    manifestPackage = manifestPackage,
                    verified = hasManifest && hasDex && !manifestPackage.isNullOrBlank()
                )
            }
        }.getOrElse { throwable ->
            ApkFileVerification(
                hasManifest = false,
                hasDex = false,
                hasResources = false,
                dexCount = 0,
                manifestPackage = null,
                verified = false,
                error = throwable.message ?: "The APK could not be read."
            )
        }
    }
}
