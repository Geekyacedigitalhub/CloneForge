package com.cloneforge.app

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

data class ApkSource(val packageName: String, val label: String, val versionName: String, val versionCode: Long, val sourceApk: File)

class CloneEngine(private val context: Context) {
    fun inspect(info: ApplicationInfo): ApkSource? {
        val pm = context.packageManager
        val packageInfo = runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull() ?: return null
        val source = File(info.sourceDir ?: return null)
        if (!source.exists() || !source.isFile) return null
        return ApkSource(info.packageName, pm.getApplicationLabel(info).toString(), packageInfo.versionName ?: "unknown", packageInfo.longVersionCode, source)
    }

    fun prepareWorkspace(source: ApkSource): File {
        val safe = source.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.filesDir, "clones/$safe").also { it.mkdirs() }
    }

    fun copySourceApk(source: ApkSource, workspace: File): File {
        val destination = File(workspace, "source.apk")
        source.sourceApk.inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
        return destination
    }
}
