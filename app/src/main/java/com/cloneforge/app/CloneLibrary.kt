package com.cloneforge.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CloneRecord(
    val cloneName: String,
    val sourcePackage: String,
    val clonePackage: String,
    val apkPath: String,
    val createdAt: Long
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
                            item.getLong("createdAt")
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
            ?.filter { it.isDirectory && it.name !in referencedPackages }
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
            })
        }
        metadataFile.writeText(array.toString())
    }
}
