package com.cloneforge.app

import java.io.File
import java.util.zip.ZipFile

data class ApkInspection(
    val apkPath: String,
    val hasManifest: Boolean,
    val hasResources: Boolean,
    val hasDex: Boolean,
    val dexCount: Int,
    val nativeLibraryCount: Int,
    val assetCount: Int
)

class ApkInspector {
    fun inspect(apk: File): ApkInspection {
        require(apk.exists() && apk.isFile) { "APK does not exist: " + apk.absolutePath }

        ZipFile(apk).use { zip ->
            var hasManifest = false
            var hasResources = false
            var hasDex = false
            var dexCount = 0
            var nativeLibraryCount = 0
            var assetCount = 0

            zip.entries().asSequence().forEach { entry ->
                val name = entry.name
                when {
                    name == "AndroidManifest.xml" -> hasManifest = true
                    name == "resources.arsc" -> hasResources = true
                    name.matches(Regex("classes([2-9][0-9]*)?\\\\.dex")) -> {
                        hasDex = true
                        dexCount++
                    }
                    name.startsWith("lib/") && name.endsWith(".so") -> nativeLibraryCount++
                    name.startsWith("assets/") && !entry.isDirectory -> assetCount++
                }
            }

            return ApkInspection(apk.absolutePath, hasManifest, hasResources, hasDex, dexCount, nativeLibraryCount, assetCount)
        }
    }
}