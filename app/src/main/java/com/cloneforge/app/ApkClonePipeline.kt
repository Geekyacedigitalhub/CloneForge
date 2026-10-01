package com.cloneforge.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class UnsignedCloneArtifact(
    val apkFile: File,
    val sourcePackage: String,
    val clonePackage: String,
    val cloneName: String,
    val manifestChanged: Boolean
)

class ApkClonePipeline(
    private val workspaceManager: ApkWorkspaceManager = ApkWorkspaceManager(),
    private val manifestTransformer: BinaryManifestTransformer = BinaryManifestTransformer()
) {

    fun buildUnsignedClone(
        sourceApk: File,
        workspaceRoot: File,
        plan: ClonePlan
    ): Result<UnsignedCloneArtifact> = runCatching {
        require(sourceApk.exists() && sourceApk.isFile) { "Source APK does not exist." }
        require(plan.sourcePackage.isNotBlank()) { "Source package is empty." }
        require(plan.clonePackage.isNotBlank()) { "Clone package is empty." }
        require(plan.cloneName.isNotBlank()) { "Clone name is empty." }

        val workspace = workspaceManager.create(workspaceRoot, sourceApk)
        workspaceManager.unpack(workspace)

        val manifestFile = File(workspace.unpackedDir, "AndroidManifest.xml")
        require(manifestFile.exists() && manifestFile.isFile) {
            "AndroidManifest.xml was not found in the APK."
        }

        val originalManifest = manifestFile.readBytes()
        val rewrite = manifestTransformer.rewrite(
            manifest = originalManifest,
            sourcePackage = plan.sourcePackage,
            clonePackage = plan.clonePackage,
            cloneLabel = plan.cloneName
        ).getOrThrow()

        manifestFile.writeBytes(rewrite.data)

        val output = workspaceManager.outputApk(
            workspace,
            plan.cloneName + "-unsigned.apk"
        )
        packUnsignedApk(workspace.unpackedDir, output)

        UnsignedCloneArtifact(
            apkFile = output,
            sourcePackage = plan.sourcePackage,
            clonePackage = plan.clonePackage,
            cloneName = plan.cloneName,
            manifestChanged = rewrite.packageChanged || rewrite.labelChanged
        )
    }

    private fun packUnsignedApk(sourceDir: File, outputApk: File) {
        require(sourceDir.exists() && sourceDir.isDirectory) { "Unpacked APK directory is missing." }
        outputApk.parentFile?.mkdirs()

        ZipOutputStream(FileOutputStream(outputApk)).use { zip ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(sourceDir).invariantSeparatorsPath }
                .forEach { file ->
                    val relative = file.relativeTo(sourceDir).invariantSeparatorsPath
                    if (isOldSignatureEntry(relative)) return@forEach

                    val entry = ZipEntry(relative).apply {
                        time = 0L
                    }
                    zip.putNextEntry(entry)
                    FileInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun isOldSignatureEntry(path: String): Boolean {
        if (!path.startsWith("META-INF/", ignoreCase = true)) return false
        val name = path.substringAfterLast('/').uppercase()
        return name.endsWith(".SF") ||
            name.endsWith(".RSA") ||
            name.endsWith(".DSA") ||
            name == "MANIFEST.MF"
    }
}