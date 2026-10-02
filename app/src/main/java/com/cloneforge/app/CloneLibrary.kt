package com.cloneforge.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CloneRecord(
    val cloneName: String,
    val sourcePackage: String,
    val clonePackage: String,
    val apkPath: String,
    val createdAt: Long,
    val sha256: String = ""
)

data class CloneIntegrityIssue(
    val clonePackage: String,
    val cloneName: String,
    val message: String
)

data class CloneIntegrityReport(
    val checked: Int,
    val healthy: Int,
    val issues: List<CloneIntegrityIssue>,
    val signaturesChecked: Int = 0,
    val signaturesVerified: Int = 0,
    val checksumBaselinesCreated: Int = 0
) {
    val isHealthy: Boolean get() = issues.isEmpty()
}

data class ImportedClone(
    val record: CloneRecord,
    val replacedExisting: Boolean
)

internal object CloneBackupPathValidator {
    fun normalizeEntryName(name: String): String {
        val safeName = name.replace('\\\\', '/')
        require(safeName.isNotBlank() && !safeName.startsWith("/")) {
            "Backup contains an unsafe path."
        }
        require(!safeName.split('/').any { it == ".." }) {
            "Backup contains an unsafe path."
        }
        return safeName
    }

    fun isApkEntry(name: String): Boolean =
        name.startsWith("apks/") &&
            name.substringAfter("apks/").matches(Regex("[0-9]+\\\\.apk"))
}

class CloneLibrary(private val context: Context) {
    companion object {
        private const val MAX_BACKUP_ENTRIES = 512
        private const val MAX_BACKUP_CLONES = 256
        private const val MAX_BACKUP_BYTES = 512L * 1024L * 1024L
    }

    private val metadataFile get() = File(context.filesDir, "clone-library.json")
    private val buildRoot get() = File(context.filesDir, "clone-builds")
    private val integrityCache = ConcurrentHashMap<String, Triple<Long, Long, String>>()
    private val integrityDetails = ConcurrentHashMap<String, String>()
    private val metadataLock = Any()

    fun integrityLabel(record: CloneRecord): String {
        val file = File(record.apkPath)
        if (!file.exists() || !file.isFile) return "Integrity: APK missing"
        if (record.sha256.isBlank()) return "Integrity: Not checked"

        val fingerprint = Pair(file.length(), file.lastModified())
        val cached = integrityCache[record.apkPath]
        if (cached?.first == fingerprint.first && cached.second == fingerprint.second) {
            return cached.third
        }

        integrityDetails.remove(record.apkPath)
        return "Integrity: Checking…"
    }

    fun verifyIntegrityLabel(record: CloneRecord): String {
        val file = File(record.apkPath)
        if (!file.exists() || !file.isFile) return "Integrity: APK missing"
        if (record.sha256.isBlank()) return "Integrity: Not checked"

        val fingerprint = Pair(file.length(), file.lastModified())
        val cached = integrityCache[record.apkPath]
        if (cached?.first == fingerprint.first && cached.second == fingerprint.second) {
            return cached.third
        }

        integrityDetails.remove(record.apkPath)
        val label = if (sha256(file).equals(record.sha256, ignoreCase = true)) {
            "Integrity: Verified"
        } else {
            "Integrity: Checksum mismatch"
        }
        integrityCache[record.apkPath] = Triple(fingerprint.first, fingerprint.second, label)
        return label
    }

    fun integrityDetail(record: CloneRecord): String? {
        val file = File(record.apkPath)
        if (!file.exists() || !file.isFile) return null
        if (record.sha256.isBlank()) return null

        val fingerprint = Pair(file.length(), file.lastModified())
        val cached = integrityCache[record.apkPath]
        if (cached?.first != fingerprint.first || cached.second != fingerprint.second) return null

        return integrityDetails[record.apkPath]
    }

    fun invalidateIntegrityCache() {
        integrityCache.clear()
        integrityDetails.clear()
    }

    fun list(): List<CloneRecord> = runCatching {
        readStoredRecords()
            .filter { File(it.apkPath).exists() }
            .sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    private fun cacheIntegrityLabel(record: CloneRecord, file: File, label: String, detail: String? = null) {
        integrityCache[record.apkPath] = Triple(file.length(), file.lastModified(), label)
        if (detail.isNullOrBlank()) integrityDetails.remove(record.apkPath)
        else integrityDetails[record.apkPath] = detail
    }

    fun checkIntegrity(): CloneIntegrityReport {
        val records = runCatching { readStoredRecords() }.getOrElse {
            return CloneIntegrityReport(
                checked = 0,
                healthy = 0,
                issues = listOf(
                    CloneIntegrityIssue(
                        clonePackage = "unknown",
                        cloneName = "Clone Library",
                        message = "Clone library metadata could not be read: " +
                            (it.message ?: "unknown error") + "."
                    )
                )
            )
        }
        val issues = mutableListOf<CloneIntegrityIssue>()
        var signaturesChecked = 0
        var signaturesVerified = 0
        var checksumBaselinesCreated = 0

        records.forEach { record ->
            val file = File(record.apkPath)
            if (!file.exists() || !file.isFile) {
                integrityCache.remove(record.apkPath)
                integrityDetails.remove(record.apkPath)
                issues += CloneIntegrityIssue(
                    record.clonePackage,
                    record.cloneName,
                    "APK file is missing."
                )
                return@forEach
            }

            val verification = ApkFileVerifier().verify(file)
            if (!verification.verified) {
                cacheIntegrityLabel(record, file, "Integrity: Verification failed", verification.error ?: "APK structure could not be verified.")
                issues += CloneIntegrityIssue(
                    record.clonePackage,
                    record.cloneName,
                    verification.error ?: "APK structure could not be verified."
                )
                return@forEach
            }

            val signatureVerification = ApkSignatureVerifier().verify(file)
            signaturesChecked++
            if (!signatureVerification.verified) {
                cacheIntegrityLabel(record, file, "Integrity: Verification failed", signatureVerification.error ?: "APK cryptographic signature could not be verified.")
                issues += CloneIntegrityIssue(
                    record.clonePackage,
                    record.cloneName,
                    signatureVerification.error ?: "APK cryptographic signature could not be verified."
                )
                return@forEach
            }
            signaturesVerified++

            if (verification.manifestPackage != record.clonePackage) {
                cacheIntegrityLabel(record, file, "Integrity: Verification failed", "Manifest package is " + (verification.manifestPackage ?: "unknown") + ".")
                issues += CloneIntegrityIssue(
                    record.clonePackage,
                    record.cloneName,
                    "Manifest package is " + (verification.manifestPackage ?: "unknown") + "."
                )
                return@forEach
            }

            val actual = calculateSha256(file)
            if (record.sha256.isBlank()) {
                updateStoredChecksum(record, actual)
                checksumBaselinesCreated++
                cacheIntegrityLabel(record, file, "Integrity: Verified", "SHA-256 baseline created during this verification.")
                return@forEach
            }
            if (!actual.equals(record.sha256, ignoreCase = true)) {
                cacheIntegrityLabel(record, file, "Integrity: Checksum mismatch", "SHA-256 checksum does not match the library record.")
                issues += CloneIntegrityIssue(
                    record.clonePackage,
                    record.cloneName,
                    "SHA-256 checksum does not match the library record."
                )
                return@forEach
            }

            cacheIntegrityLabel(record, file, "Integrity: Verified")
        }

        return CloneIntegrityReport(
            checked = records.size,
            healthy = records.size - issues.size,
            issues = issues,
            signaturesChecked = signaturesChecked,
            signaturesVerified = signaturesVerified,
            checksumBaselinesCreated = checksumBaselinesCreated
        )
    }

    fun checkIntegrity(record: CloneRecord): CloneIntegrityReport {
        val file = File(record.apkPath)
        if (!file.exists() || !file.isFile) {
            integrityCache.remove(record.apkPath)
            integrityDetails.remove(record.apkPath)
            return CloneIntegrityReport(
                checked = 1,
                healthy = 0,
                issues = listOf(CloneIntegrityIssue(record.clonePackage, record.cloneName, "APK file is missing."))
            )
        }

        val verification = ApkFileVerifier().verify(file)
        if (!verification.verified) {
            val message = verification.error ?: "APK structure could not be verified."
            cacheIntegrityLabel(record, file, "Integrity: Verification failed", message)
            return CloneIntegrityReport(
                checked = 1,
                healthy = 0,
                issues = listOf(CloneIntegrityIssue(record.clonePackage, record.cloneName, message))
            )
        }

        val signatureVerification = ApkSignatureVerifier().verify(file)
        if (!signatureVerification.verified) {
            val message = signatureVerification.error ?: "APK cryptographic signature could not be verified."
            cacheIntegrityLabel(record, file, "Integrity: Verification failed", message)
            return CloneIntegrityReport(
                checked = 1,
                healthy = 0,
                issues = listOf(CloneIntegrityIssue(record.clonePackage, record.cloneName, message)),
                signaturesChecked = 1,
                signaturesVerified = 0
            )
        }

        if (verification.manifestPackage != record.clonePackage) {
            val message = "Manifest package is " + (verification.manifestPackage ?: "unknown") + "."
            cacheIntegrityLabel(record, file, "Integrity: Verification failed", message)
            return CloneIntegrityReport(
                checked = 1,
                healthy = 0,
                issues = listOf(CloneIntegrityIssue(record.clonePackage, record.cloneName, message)),
                signaturesChecked = 1,
                signaturesVerified = 1
            )
        }

        val actual = calculateSha256(file)
        if (record.sha256.isBlank()) {
            updateStoredChecksum(record, actual)
            cacheIntegrityLabel(record, file, "Integrity: Verified", "SHA-256 baseline created during this verification.")
            return CloneIntegrityReport(
                checked = 1,
                healthy = 1,
                issues = emptyList(),
                signaturesChecked = 1,
                signaturesVerified = 1,
                checksumBaselinesCreated = 1
            )
        }
        if (!actual.equals(record.sha256, ignoreCase = true)) {
            val message = "SHA-256 checksum does not match the library record."
            cacheIntegrityLabel(record, file, "Integrity: Checksum mismatch", message)
            return CloneIntegrityReport(
                checked = 1,
                healthy = 0,
                issues = listOf(CloneIntegrityIssue(record.clonePackage, record.cloneName, message)),
                signaturesChecked = 1,
                signaturesVerified = 1
            )
        }
        cacheIntegrityLabel(record, file, "Integrity: Verified")

        return CloneIntegrityReport(
            checked = 1,
            healthy = 1,
            issues = emptyList(),
            signaturesChecked = 1,
            signaturesVerified = 1
        )
    }


    private fun updateStoredChecksum(record: CloneRecord, sha256: String) {
        synchronized(metadataLock) {
            val updatedRecords = readStoredRecords().map { stored ->
                if (stored.clonePackage == record.clonePackage && stored.apkPath == record.apkPath) {
                    stored.copy(sha256 = sha256)
                } else {
                    stored
                }
            }
            writeRecords(updatedRecords)
        }
    }

    fun findByClonePackage(clonePackage: String): CloneRecord? =
        list().firstOrNull { it.clonePackage == clonePackage }

    fun add(record: CloneRecord) {
        synchronized(metadataLock) {
            val records = list().filterNot {
                it.apkPath == record.apkPath || it.clonePackage == record.clonePackage
            } + record
            writeRecords(records)
            integrityCache.remove(record.apkPath)
        }
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

                        val safeName = CloneBackupPathValidator.normalizeEntryName(entry.name)

                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }

                        require(seenEntries.add(safeName)) {
                            "Backup contains duplicate entries."
                        }

                        val destination = when {
                            safeName == "cloneforge-backup.json" -> metadataFile
                            CloneBackupPathValidator.isApkEntry(safeName) -> {
                                File(restoreRoot, safeName.substringAfter("apks/"))
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

            data class PreparedRestore(
                val item: JSONObject,
                val packageName: String,
                val sourceApk: File,
                val actualSha256: String
            )

            val prepared = mutableListOf<PreparedRestore>()
            val referencedEntries = mutableSetOf<String>()
            val packageNames = mutableSetOf<String>()

            // Phase 1: validate the complete backup without modifying the library.
            for (i in 0 until clones.length()) {
                val item = clones.getJSONObject(i)
                val entryName = CloneBackupPathValidator.normalizeEntryName(item.getString("apkEntry"))
                require(CloneBackupPathValidator.isApkEntry(entryName)) {
                    "Backup contains an invalid APK reference."
                }
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
                require(packageNames.add(packageName)) {
                    "Backup contains duplicate clone packages."
                }

                val expectedSha256 = item.optString("sha256", "")
                val actualSha256 = calculateSha256(sourceApk)
                if (expectedSha256.isNotBlank()) {
                    require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                        "SHA-256 mismatch for " + item.optString("cloneName", "clone") + "."
                    }
                }

                prepared += PreparedRestore(
                    item = item,
                    packageName = packageName,
                    sourceApk = sourceApk,
                    actualSha256 = actualSha256
                )
            }

            // Phase 2: stage all replacements before changing the library metadata.
            synchronized(metadataLock) {
                val existingRecords = readStoredRecords()
                val restoredPackages = prepared.mapTo(mutableSetOf()) { it.packageName }
                val oldRecords = existingRecords.filter { it.clonePackage in restoredPackages }
                val staged = mutableListOf<Pair<File, File>>()
                val backups = mutableListOf<Pair<File, File>>()
                var metadataCommitted = false
                try {
                    for (restore in prepared) {
                        val item = restore.item
                        val destinationDir = File(
                            buildRoot,
                            "imports/" + sanitize(restore.packageName)
                        ).apply { mkdirs() }
                        val destination = File(
                            destinationDir,
                            sanitize(item.optString("cloneName", "Restored Clone")) + ".apk"
                        )
                        val stagedFile = File(
                            restoreRoot,
                            "staged-" + sanitize(restore.packageName) + ".apk"
                        )
                        restore.sourceApk.copyTo(stagedFile, overwrite = true)
                        staged += stagedFile to destination
                    }

                    val pathsToBackup = (oldRecords.map { File(it.apkPath) } + staged.map { it.second })
                        .filter { it.exists() && it.isFile }
                        .distinctBy { it.absolutePath }
                    pathsToBackup.forEachIndexed { index, source ->
                        val backup = File(restoreRoot, "backup-$index.apk")
                        source.copyTo(backup, overwrite = true)
                        backups += source to backup
                    }

                    for ((stagedFile, destination) in staged) {
                        destination.parentFile?.mkdirs()
                        stagedFile.copyTo(destination, overwrite = true)
                    }

                    val updatedRecords = existingRecords.filterNot {
                        it.clonePackage in restoredPackages
                    }.toMutableList()

                    prepared.forEach { restore ->
                        val item = restore.item
                        val destination = File(
                            buildRoot,
                            "imports/" + sanitize(restore.packageName) + "/" +
                                sanitize(item.optString("cloneName", "Restored Clone")) + ".apk"
                        )
                        updatedRecords += CloneRecord(
                            cloneName = item.optString("cloneName", "Restored Clone"),
                            sourcePackage = item.optString("sourcePackage", "Restored Backup"),
                            clonePackage = restore.packageName,
                            apkPath = destination.absolutePath,
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            sha256 = restore.actualSha256
                        )
                    }

                    writeRecords(updatedRecords)
                    metadataCommitted = true

                    oldRecords.forEach { oldRecord ->
                        val oldFile = File(oldRecord.apkPath)
                        if (oldFile.absolutePath !in staged.map { it.second.absolutePath }) {
                            oldFile.delete()
                        }
                        File(buildRoot, sanitize(oldRecord.clonePackage)).deleteRecursively()
                    }
                } catch (throwable: Throwable) {
                    if (metadataCommitted) {
                        runCatching { writeRecords(existingRecords) }
                    }
                    val backedUpPaths = backups.mapTo(mutableSetOf()) { it.first.absolutePath }
                    staged.forEach { (_, destination) ->
                        if (destination.absolutePath !in backedUpPaths) {
                            runCatching { destination.delete() }
                        }
                    }
                    for ((destination, backup) in backups) {
                        runCatching { backup.copyTo(destination, overwrite = true) }
                    }
                    throw throwable
                } finally {
                    staged.forEach { (stagedFile, _) -> stagedFile.delete() }
                    backups.forEach { (_, backup) -> backup.delete() }
                }
            }

            integrityCache.clear()
            return prepared.size
        } finally {
            restoreRoot.deleteRecursively()
        }
    }


    fun delete(record: CloneRecord): Boolean {
        synchronized(metadataLock) {
            val apkFile = File(record.apkPath)
            val deleted = !apkFile.exists() || apkFile.delete()

            val workspace = File(buildRoot, sanitize(record.clonePackage))
            workspace.deleteRecursively()

            val remaining = list().filterNot {
                it.apkPath == record.apkPath || it.clonePackage == record.clonePackage
            }
            writeRecords(remaining)
            integrityCache.remove(record.apkPath)
            return deleted
        }
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
        synchronized(metadataLock) {
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
    }

    private fun readStoredRecords(): List<CloneRecord> {
        if (!metadataFile.exists()) return emptyList()

        val array = JSONArray(metadataFile.readText())
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    CloneRecord(
                        item.getString("cloneName"),
                        item.getString("sourcePackage"),
                        item.getString("clonePackage"),
                        item.getString("apkPath"),
                        item.getLong("createdAt"),
                        item.optString("sha256", "")
                    )
                )
            }
        }
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
