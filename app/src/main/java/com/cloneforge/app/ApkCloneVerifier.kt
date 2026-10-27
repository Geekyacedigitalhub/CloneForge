package com.cloneforge.app

import java.io.File
import java.util.zip.ZipFile

data class ApkCloneVerification(
    val manifestPackage: String?,
    val expectedClonePackage: String,
    val hasManifest: Boolean,
    val hasDex: Boolean,
    val hasResources: Boolean,
    val dexFilesChecked: Int,
    val sourceDescriptorOccurrences: Int,
    val cloneDescriptorOccurrences: Int,
    val verified: Boolean
)

class ApkCloneVerifier(
    private val manifestReader: BinaryManifestReader = BinaryManifestReader()
) {
    fun verify(
        apkFile: File,
        sourcePackage: String,
        clonePackage: String,
        requireCloneDescriptors: Boolean = true,
        preserveClasses: Boolean = false
    ): ApkCloneVerification {
        require(apkFile.exists() && apkFile.isFile) { "APK file does not exist." }

        var manifestPackage: String? = null
        var hasManifest = false
        var hasDex = false
        var hasResources = false
        var dexFilesChecked = 0
        var sourceOccurrences = 0
        var cloneOccurrences = 0

        ZipFile(apkFile).use { zip ->
            hasManifest = zip.getEntry("AndroidManifest.xml") != null
            hasResources = zip.getEntry("resources.arsc") != null

            if (hasManifest) {
                val manifest = zip.getEntry("AndroidManifest.xml")
                    ?: error("Cloned APK is missing AndroidManifest.xml.")
                manifestPackage = manifestReader.read(
                    zip.getInputStream(manifest).use { it.readBytes() }
                ).packageName
            }

            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes([2-9]|[1-9][0-9]+)?\\.dex")) }
                .forEach { entry ->
                    hasDex = true
                    dexFilesChecked++
                    if (!preserveClasses) {
                        val verification = DexDescriptorVerifier().verifyBytes(
                            zip.getInputStream(entry).use { it.readBytes() },
                            sourcePackage,
                            clonePackage
                        )
                        sourceOccurrences += verification.sourceOccurrences
                        cloneOccurrences += verification.cloneOccurrences
                    }
                }
        }

        val verified = hasManifest &&
            hasDex &&
            manifestPackage == clonePackage &&
            (preserveClasses || (
                sourceOccurrences == 0 &&
                    (!requireCloneDescriptors || cloneOccurrences > 0)
                ))

        return ApkCloneVerification(
            manifestPackage = manifestPackage,
            expectedClonePackage = clonePackage,
            hasManifest = hasManifest,
            hasDex = hasDex,
            hasResources = hasResources,
            dexFilesChecked = dexFilesChecked,
            sourceDescriptorOccurrences = sourceOccurrences,
            cloneDescriptorOccurrences = cloneOccurrences,
            verified = verified
        )
    }
}
