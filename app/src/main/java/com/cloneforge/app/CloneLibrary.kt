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
    companion object {
        private const val MAX_BACKUP_ENTRIES = 512
        private const val MAX_BACKUP_CLONES = 256
        private const val MAX_BACKUP_BYTES = 512L * 1024L * 1024L
    }

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


    fun exportBackup(uri: Uri): Int {
        cleanupStaleRecords()
        val records = list()
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Unable to open the selected backup destination.")

        output.use { stream ->
            java.util.zip.ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("cloneforge-backup.json"))
                zip.write(JSONObject().apply {
                    put("format", "CloneForge")
                    put("version", 1)
                    put("createdAt", System.currentTimeMillis())
                    put("clones", JSONArray().apply {
                        records.forEachIndexed { index, record ->
                            put(JSONObject().apply {
                                put("index", index)
                                put("cloneName", record.cloneName)
                                put("sourcePackage", record.sourcePackage)
                                put("clonePackage", record.clonePackage)
                                put("createdAt", record.createdAt)
                                put("sha256", record.sha256)
                                put("apkEntry", "apks/" + index + ".apk")
                            })
                        }
                    })
                }.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                records.forEachIndexed { index, record ->
                    val apk = File(record.apkPath)
                    require(apk.exists() && apk.isFile) {
                        "Missing clone APK: " + record.cloneName
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry("apks/" + index + ".apk"))
                    apk.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return records.size
    }

    fun restoreBackup(uri: Uri): Int {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Unable to open the selected backup.")

        val restoreRoot = File(
            buildRoot,
            "restore-" + System.currentTimeMillis() + "-" + System.nanoTime()
        ).apply { mkdirs() }
        val metadataFile = File(restoreRoot, "cloneforge-backup.json")

        try {
            input.use { source ->
                java.util.zip.ZipInputStream(source).use { zip ->
                    val seenEntries = mutableSetOf<String>()
                    var entryCount = 0
                    var extractedBytes = 0L

                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entryCount++
                        require(entryCount <= MAX_BACKUP_ENTRIES) {
                            "Backup contains too many files."
                        }

                        val safeName = entry.name.replace('\\', '/')
                        require(safeName.isNotBlank() && !safeName.startsWith("/")) {
                            "Backup contains an unsafe path."
                        }

                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }

                        require(seenEntries.add(safeName)) {
                            "Backup contains duplicate entries."
                        }

                        val destination = when {
                            safeName == "cloneforge-backup.json" -> metadataFile
                            safeName.startsWith("apks/") &&
                                safeName.substringAfter("apks/").matches(Regex("[0-9]+\\.apk")) -> {
                                File(
                                    restoreRoot,
                                    safeName.substringAfter("apks/")
                                )
                            }
                            else -> error("Backup contains an unsupported or unsafe entry.")
                        }

                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read == -1) break
                                extractedBytes += read
                                require(extractedBytes <= MAX_BACKUP_BYTES) {
                                    "Backup is too large to restore safely."
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            require(metadataFile.exists()) { "Backup metadata is missing." }
            val metadata = JSONObject(metadataFile.readText())
            require(metadata.optString("format") == "CloneForge") {
                "This file is not a CloneForge backup."
            }
            require(metadata.optInt("version", -1) == 1) {
                "This CloneForge backup version is not supported."
            }

            val clones = metadata.optJSONArray("clones") ?: JSONArray()
            require(clones.length() <= MAX_BACKUP_CLONES) {
                "Backup contains too many clones."
            }

            val referencedEntries = mutableSetOf<String>()
            var restored = 0

            for (i in 0 until clones.length()) {
                val item = clones.getJSONObject(i)
                val entryName = item.getString("apkEntry").replace('\\', '/')
                require(
                    entryName.startsWith("apks/") &&
                        entryName.substringAfter("apks/").matches(Regex("[0-9]+\\.apk"))
                ) { "Backup contains an invalid APK reference." }
                require(referencedEntries.add(entryName)) {
                    "Backup contains duplicate APK references."
                }

                val sourceApk = File(restoreRoot, entryName.substringAfter("apks/"))
                require(sourceApk.exists() && sourceApk.isFile) {
                    "Backup APK is missing: " + entryName
                }

                val verification = ApkFileVerifier().verify(sourceApk)
                require(verification.verified) {
                    verification.error ?: "Backup APK could not be verified."
                }

                val packageName = verification.manifestPackage
                    ?.takeIf { it.isNotBlank() }
                    ?: error("Backup APK package name is missing.")

                val declaredPackage = item.getString("clonePackage")
                require(packageName == declaredPackage) {
                    "Backup package mismatch for " + item.optString("cloneName", "clone") + "."
                }

                val destinationDir = File(
                    buildRoot,
                    "imports/" + sanitize(packageName)
                ).apply { mkdirs() }
                val destination = File(
                    destinationDir,
                    sanitize(item.optString("cloneName", "Restored Clone")) + ".apk"
                )
                sourceApk.copyTo(destination, overwrite = true)

                val existing = findByClonePackage(packageName)
                existing?.let {
                    if (File(it.apkPath).absolutePath != destination.absolutePath) {
                        File(it.apkPath).delete()
                    }
                    File(buildRoot, sanitize(it.clonePackage)).deleteRecursively()
                }

                val actualSha256 = calculateSha256(destination)
                val expectedSha256 = item.optString("sha256", "")
                if (expectedSha256.isNotBlank()) {
                    require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                        "SHA-256 mismatch for " + item.optString("cloneName", "clone") + "."
                    }
                }

                add(
                    CloneRecord(
                        cloneName = item.optString("cloneName", "Restored Clone"),
                        sourcePackage = item.optString("sourcePackage", "Restored Backup"),
                        clonePackage = packageName,
                        apkPath = destination.absolutePath,
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        sha256 = actualSha256
                    )
                )
                restored++
            }

            return restored
        } finally {
            restoreRoot.deleteRecursively()
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
