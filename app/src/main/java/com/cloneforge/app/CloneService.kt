package com.cloneforge.app

import android.content.Context
import java.io.File

class CloneService(private val context: Context) {

    private val engine = CloneEngine(context)
    private val pipeline = ApkClonePipeline()

    fun buildInstalledAppClone(
        sourcePackage: String,
        cloneName: String,
        cloneIndex: Int
    ): Result<UnsignedCloneArtifact> = runCatching {
        val applicationInfo = context.packageManager.getApplicationInfo(sourcePackage, 0)
        val source = engine.inspect(applicationInfo)
            ?: error("Unable to inspect the selected installed app.")
        val workspace = engine.prepareWorkspace(source)
        engine.copySourceApk(source, workspace)

        val plan = ClonePlanner.create(
            sourcePackage = source.packageName,
            cloneName = cloneName,
            cloneIndex = cloneIndex
        )

        pipeline.buildUnsignedClone(
            sourceApk = File(workspace, "source.apk"),
            workspaceRoot = File(
                context.filesDir,
                "clone-builds/${sanitize(source.packageName)}-$cloneIndex"
            ),
            plan = plan
        ).getOrThrow()
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }
}