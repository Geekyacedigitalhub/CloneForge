package com.cloneforge.app

import java.io.File
import java.util.zip.ZipFile

data class ApkWorkspace(val root: File, val inputApk: File, val unpackedDir: File, val outputDir: File)

class ApkWorkspaceManager {
    fun create(root: File, sourceApk: File): ApkWorkspace {
        require(sourceApk.exists() && sourceApk.isFile) { "Source APK does not exist." }
        val unpacked = File(root, "unpacked")
        val output = File(root, "output")
        unpacked.deleteRecursively()
        output.deleteRecursively()
        unpacked.mkdirs()
        output.mkdirs()
        return ApkWorkspace(root, sourceApk, unpacked, output)
    }

    fun unpack(workspace: ApkWorkspace) {
        ZipFile(workspace.inputApk).use { zip ->
            zip.entries().asSequence().forEach { archiveEntry ->
                val target = File(workspace.unpackedDir, archiveEntry.name)
                val canonicalRoot = workspace.unpackedDir.canonicalFile
                val canonicalTarget = target.canonicalFile
                require(canonicalTarget.path == canonicalRoot.path || canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Unsafe APK archive path: " + archiveEntry.name
                }
                if (archiveEntry.isDirectory) {
                    canonicalTarget.mkdirs()
                } else {
                    canonicalTarget.parentFile?.mkdirs()
                    zip.getInputStream(archiveEntry).use { input ->
                        canonicalTarget.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    fun outputApk(workspace: ApkWorkspace, fileName: String): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "clone.apk" }
        return File(workspace.outputDir, safeName)
    }
}