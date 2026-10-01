package com.cloneforge.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CloneRecord(
    val cloneName: String,
    val sourcePackage: String,
    val clonePackage: String,
    val apkPath: String,
    val createdAt: Long,
    val sha256: String = ""
)

data class ImportedClone(
    val record: CloneRecord,
    val replacedExisting: Boolean
)

class CloneLibrary(private val context: Context) {
    private val metadataFile get() = File(context.filesDir, "clone-library.json")
    private val buildRoot get() = File(context.filesDir, "clone-builds")

    fun list(): List<CloneRecord> = runCatching {
        if (!metadataFile.exists()) return emptyList()
        val array = JSONArray(metadataFile.readText())
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val path = item.getString("apkPath")
                if (File(path).exists()) {
                    add(
                        CloneRecord(
                            item.getString("cloneName"),
                            item.getString("sourcePackage"),
                            item.getString("clonePackage"),
                            path,
                            item.getLong("createdAt"),
                            item.optString("sha256", "")
                        )
                    )
                }
            }
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    fun findByClonePackage(clonePackage: String): CloneRecord? =
        list().firstOrNull { it.clonePackage == clonePackage }

    fun add(record: CloneRecord) {
        val records = list().filterNot {
            it.apkPath == record.apkPath || it.clonePackage == record.clonePackage
        } + record
        writeRecords(records)
    }

    fun importApk(uri: Uri): ImportedClone {
        val importRoot = File(buildRoot, "imports").apply { mkdirs() }
        val tempFile = File(
            importRoot,
            "incoming-" + System.currentTimeMillis() + "-" + System.nanoTime() + ".apk"
        )

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open the selected APK.")

            input.use { source ->
                tempFile.outputStream().use { target ->
                    source.copyTo(target)
                }
            }

            val fileVerification = ApkFileVerifier().verify(tempFile)
            require(fileVerification.verified) {
                fileVerification.error
                    ?: "The selected file is not a readable APK with a manifest and DEX bytecode."
            }

            val manifestPackage = fileVerification.manifestPackage
                ?.takeIf { it.isNotBlank() }
                ?: error("The selected APK does not contain a readable package name.")

            val manifestInfo = java.util.zip.ZipFile(tempFile).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                    ?: error("The selected APK is missing AndroidManifest.xml.")
                BinaryManifestReader().read(
                    zip.getInputStream(manifestEntry).use { it.readBytes() }
                )
            }

            val clonePackage = manifestInfo.packageName
                ?.takeIf { it.isNotBlank() }
                ?: manifestPackage

            require(clonePackage == manifestPackage) {
                "The APK manifest package could not be read consistently."
            }

            val sha256 = calculateSha256(tempFile)
            val originalName = manifestInfo.applicationLabel?.takeIf { it.isNotBlank() }
                ?: tempFile.nameWithoutExtension.ifBlank { "Imported Clone" }

            val existing = findByClonePackage(clonePackage)
            val destinationDir = File(importRoot, sanitize(clonePackage)).apply {
                mkdirs()
            }
            val destination = File(destinationDir, sanitize(originalName) + ".apk")

            tempFile.copyTo(destination, overwrite = true)

            existing?.let {
                File(it.apkPath).delete()
                File(buildRoot, sanitize(it.clonePackage))
                    .takeIf { workspace -> workspace != destinationDir }
                    ?.deleteRecursively()
            }

            val record = CloneRecord(
                cloneName = originalName,
                sourcePackage = "Imported APK",
                clonePackage = clonePackage,
                apkPath = destination.absolutePath,
                createdAt = System.currentTimeMillis(),
                sha256 = sha256
            )

            add(record)
            return ImportedClone(record, replacedExisting = existing != null)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    fun delete(record: CloneRecord): Boolean {
        val apkFile = File(record.apkPath)
        val deleted = !apkFile.exists() || apkFile.delete()

        val workspace = File(buildRoot, sanitize(record.clonePackage))
        workspace.deleteRecursively()

        val remaining = list().filterNot {
            it.apkPath == record.apkPath || it.clonePackage == record.clonePackage
        }
        writeRecords(remaining)
        return deleted
    }

    fun cleanupOrphanedBuildWorkspaces() {
        if (!buildRoot.exists()) return

        val referencedPackages = list()
            .mapTo(mutableSetOf()) { sanitize(it.clonePackage) }

        buildRoot.listFiles()
            ?.filter { it.isDirectory && it.name !in referencedPackages && it.name != "imports" }
            ?.forEach { it.deleteRecursively() }
    }

    fun deleteAll(): Int {
        val records = list()
        var deleted = 0

        records.forEach { record ->
            if (delete(record)) {
                deleted++
            }
        }

        cleanupOrphanedBuildWorkspaces()
        return deleted
    }

    fun cleanupStaleRecords() {
        if (!metadataFile.exists()) return

        val validRecords = list()
        val validPackages = validRecords.mapTo(mutableSetOf()) { it.clonePackage }

        val array = JSONArray(metadataFile.readText())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val clonePackage = item.optString("clonePackage")
            if (clonePackage.isNotBlank() && clonePackage !in validPackages) {
                File(item.optString("apkPath")).delete()
                File(buildRoot, sanitize(clonePackage)).deleteRecursively()
            }
        }

        writeRecords(validRecords)
    }

    private fun calculateSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }

    private fun writeRecords(records: List<CloneRecord>) {
        val array = JSONArray()
        records.forEach {
            array.put(JSONObject().apply {
                put("cloneName", it.cloneName)
                put("sourcePackage", it.sourcePackage)
                put("clonePackage", it.clonePackage)
                put("apkPath", it.apkPath)
                put("createdAt", it.createdAt)
                put("sha256", it.sha256)
            })
        }
        metadataFile.writeText(array.toString())
    }
}
