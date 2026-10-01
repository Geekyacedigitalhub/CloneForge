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

    fun add(record: CloneRecord) {
        val records = list().filterNot { it.apkPath == record.apkPath } + record
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

    fun delete(record: CloneRecord): Boolean {
        val deleted = File(record.apkPath).delete()
        val remaining = list().filterNot { it.apkPath == record.apkPath }
        val array = JSONArray()
        remaining.forEach {
            array.put(JSONObject().apply {
                put("cloneName", it.cloneName)
                put("sourcePackage", it.sourcePackage)
                put("clonePackage", it.clonePackage)
                put("apkPath", it.apkPath)
                put("createdAt", it.createdAt)
            })
        }
        metadataFile.writeText(array.toString())
        return deleted
    }
}
