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
        packageSuffix: String? = null
    ): Result<CloneArtifact> = runCatching {
        val applicationInfo = context.packageManager.getApplicationInfo(sourcePackage, 0)
        val source = engine.inspect(applicationInfo)
            ?: error("Unable to inspect the selected installed app.")
        val preflightResult = preflight.inspect(source.sourceApk, source.packageName).getOrThrow()
        require(preflightResult.compatibility.supported) {
            preflightResult.compatibility.issues.joinToString(" ")
        }

        val workspace = engine.prepareWorkspace(source)
        engine.copySourceApk(source, workspace)

        val requestedIndex = cloneIndex ?: nextAvailableCloneIndex(source.packageName)

        val plan = if (packageSuffix.isNullOrBlank()) {
            ClonePlanner.create(source.packageName, cloneName, requestedIndex)
        } else {
            ClonePlanner.createWithSuffix(
                source.packageName,
                cloneName,
                packageSuffix,
                requestedIndex
            )
        }

        require(library.findByClonePackage(plan.clonePackage) == null) {
            "A clone with package ${plan.clonePackage} already exists. Use a different package suffix or delete the existing clone first."
        }

        val cloneWorkspaceRoot = File(
            context.filesDir,
            "clone-builds/${sanitize(plan.clonePackage)}"
        )

        val unsigned = pipeline.buildUnsignedClone(
            sourceApk = File(workspace, "source.apk"),
            workspaceRoot = cloneWorkspaceRoot,
            plan = plan
        ).getOrThrow()

        val signedOutput = File(
            unsigned.apkFile.parentFile,
            sanitize(plan.cloneName) + ".apk"
        )

        signer.sign(unsigned.apkFile, signedOutput).getOrThrow()

        CloneArtifact(
            apkFile = signedOutput,
            sourcePackage = plan.sourcePackage,
            clonePackage = plan.clonePackage,
            cloneName = plan.cloneName
        )
    }

    private fun nextAvailableCloneIndex(sourcePackage: String): Int {
        var index = 1
        while (library.findByClonePackage(
                PackageIdentity.createClonePackage(sourcePackage, index)
            ) != null
        ) {
            index++
        }
        return index
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }
}
