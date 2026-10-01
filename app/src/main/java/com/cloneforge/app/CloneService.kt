package com.cloneforge.app

import android.content.Context
import java.io.File

data class CloneArtifact(
    val apkFile: File,
    val sourcePackage: String,
    val clonePackage: String,
    val cloneName: String
)

class CloneService(private val context: Context) {

    private val engine = CloneEngine(context)
    private val pipeline = ApkClonePipeline()
    private val signer = ApkSignerService(context)
    private val preflight = ClonePreflightService()
    private val library = CloneLibrary(context)

    fun buildInstalledAppClone(
        sourcePackage: String,
        cloneName: String,
        cloneIndex: Int? = null,
        packageSuffix: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<CloneArtifact> = runCatching {
        onProgress?.invoke("Validating clone settings…")
        require(cloneName.isNotBlank()) { "Clone name cannot be empty." }

        val requestedIndex = cloneIndex ?: nextAvailableCloneIndex(sourcePackage)
        val plan = if (packageSuffix.isNullOrBlank()) {
            ClonePlanner.create(sourcePackage, cloneName, requestedIndex)
        } else {
            ClonePlanner.createWithSuffix(
                sourcePackage,
                cloneName,
                packageSuffix,
                requestedIndex
            )
        }

        require(library.findByClonePackage(plan.clonePackage) == null) {
            "A clone with package ${plan.clonePackage} already exists. Use a different package suffix or delete the existing clone first."
        }
        require(!isPackageInstalled(plan.clonePackage)) {
            "The package ${plan.clonePackage} is already installed. Use a different package suffix."
        }

        onProgress?.invoke("Inspecting source APK…")
        val applicationInfo = context.packageManager.getApplicationInfo(sourcePackage, 0)
        val source = engine.inspect(applicationInfo)
            ?: error("Unable to inspect the selected installed app.")
        val preflightResult = preflight.inspect(source.sourceApk, source.packageName).getOrThrow()
        require(preflightResult.compatibility.supported) {
            preflightResult.compatibility.issues.joinToString(" ")
        }

        onProgress?.invoke("Preparing clone workspace…")
        val workspace = engine.prepareWorkspace(source)
        engine.copySourceApk(source, workspace)

        val cloneWorkspaceRoot = File(
            context.filesDir,
            "clone-builds/" + sanitize(plan.clonePackage)
        )

        try {
            onProgress?.invoke("Rebuilding APK package identity…")
            val unsigned = pipeline.buildUnsignedClone(
                sourceApk = File(workspace, "source.apk"),
                workspaceRoot = cloneWorkspaceRoot,
                plan = plan
            ).getOrThrow()

            val signedOutput = File(
                unsigned.apkFile.parentFile,
                sanitize(plan.cloneName) + "-" + sanitize(plan.clonePackage) + ".apk"
            )

            onProgress?.invoke("Signing clone APK…")
            signer.sign(unsigned.apkFile, signedOutput).getOrThrow()

            onProgress?.invoke("Saving clone to library…")
            library.add(
                CloneRecord(
                    cloneName = plan.cloneName,
                    sourcePackage = plan.sourcePackage,
                    clonePackage = plan.clonePackage,
                    apkPath = signedOutput.absolutePath,
                    createdAt = System.currentTimeMillis()
                )
            )

            CloneArtifact(
                apkFile = signedOutput,
                sourcePackage = plan.sourcePackage,
                clonePackage = plan.clonePackage,
                cloneName = plan.cloneName
            )
        } catch (error: Throwable) {
            cloneWorkspaceRoot.deleteRecursively()
            throw error
        }
    }

    fun nextAvailableCloneIndexForUi(sourcePackage: String): Int =
        nextAvailableCloneIndex(sourcePackage)

    private fun nextAvailableCloneIndex(sourcePackage: String): Int {
        var index = 1
        while (true) {
            val candidate = PackageIdentity.createClonePackage(sourcePackage, index)
            if (library.findByClonePackage(candidate) == null && !isPackageInstalled(candidate)) {
                return index
            }
            index++
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }
}
