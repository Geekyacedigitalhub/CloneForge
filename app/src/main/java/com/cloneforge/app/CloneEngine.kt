package com.cloneforge.app

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

data class ApkSource(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sourceApk: File,
    val splitApks: List<File> = emptyList()
)

class CloneEngine(
    private val context: Context,
    private val splitApkMerger: SplitApkMerger = SplitApkMerger()
) {
    fun inspect(info: ApplicationInfo): ApkSource? {
        val pm = context.packageManager
        val packageInfo = runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull() ?: return null
        val source = File(info.sourceDir ?: return null)
        if (!source.exists() || !source.isFile) return null
        val splits = info.splitSourceDirs
            ?.map { File(it) }
            ?.filter { it.exists() && it.isFile }
            .orEmpty()
        return ApkSource(
            packageName = info.packageName,
            label = pm.getApplicationLabel(info).toString(),
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = packageInfo.longVersionCode,
            sourceApk = source,
            splitApks = splits
        )
    }

    fun prepareWorkspace(source: ApkSource): File {
        val safe = source.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.filesDir, "clones/$safe").also { it.mkdirs() }
    }

    fun copySourceApk(source: ApkSource, workspace: File): File {
        val destination = File(workspace, "source.apk")
        if (source.splitApks.isNotEmpty()) {
            splitApkMerger.merge(source.sourceApk, source.splitApks, destination).getOrThrow()
        } else {
            source.sourceApk.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return destination
    }
}
