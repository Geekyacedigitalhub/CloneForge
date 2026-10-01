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

    fun buildInstalledAppClone(
        sourcePackage: String,
        cloneName: String,
        cloneIndex: Int,
        packageSuffix: String? = null
    ): Result<CloneArtifact> = runCatching {
        val applicationInfo = context.packageManager.getApplicationInfo(sourcePackage, 0)
        val source = engine.inspect(applicationInfo)
            ?: error("Unable to inspect the selected installed app.")
        val workspace = engine.prepareWorkspace(source)
        engine.copySourceApk(source, workspace)

        val plan = if (packageSuffix.isNullOrBlank()) {
            ClonePlanner.create(source.packageName, cloneName, cloneIndex)
        } else {
            ClonePlanner.createWithSuffix(source.packageName, cloneName, packageSuffix, cloneIndex)
        }

        val unsigned = pipeline.buildUnsignedClone(
            sourceApk = File(workspace, "source.apk"),
            workspaceRoot = File(
                context.filesDir,
                "clone-builds/${sanitize(source.packageName)}-$cloneIndex"
            ),
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

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }
}