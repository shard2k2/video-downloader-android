package com.example.ytmpe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Represents a single download attempt
// status can be: "success", "failed", "cancelled"
data class DownloadRecord(
    val id: Long,
    val title: String,
    val url: String,
    val format: String,
    val filePath: String,
    val timestamp: Long,
    val success: Boolean,
    val status: String = if (success) "success" else "failed"
    // status defaults based on success flag so old records still work
)

object DownloadHistory {

    private const val PREFS_NAME  = "vidtown_history"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 100

    fun save(context: Context, record: DownloadRecord) {
        val prefs    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = loadAll(context).toMutableList()
        existing.add(0, record)
        val trimmed  = if (existing.size > MAX_RECORDS) existing.take(MAX_RECORDS) else existing
        val jsonArray = JSONArray()
        trimmed.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_RECORDS, jsonArray.toString()).apply()
    }

    fun loadAll(context: Context): List<DownloadRecord> {
        val prefs      = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            (0 until jsonArray.length()).map { i -> recordFromJson(jsonArray.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun delete(context: Context, id: Long) {
        val updated   = loadAll(context).filter { it.id != id }
        val prefs     = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        updated.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_RECORDS, jsonArray.toString()).apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_RECORDS).apply()
    }
}

private fun DownloadRecord.toJson(): JSONObject = JSONObject().apply {
    put("id",        id)
    put("title",     title)
    put("url",       url)
    put("format",    format)
    put("filePath",  filePath)
    put("timestamp", timestamp)
    put("success",   success)
    put("status",    status)
}

private fun recordFromJson(json: JSONObject) = DownloadRecord(
    id        = json.getLong("id"),
    title     = json.getString("title"),
    url       = json.getString("url"),
    format    = json.getString("format"),
    filePath  = json.optString("filePath", ""),
    timestamp = json.getLong("timestamp"),
    success   = json.getBoolean("success"),
    // optString with fallback means old records saved before this field
    // existed will still load correctly — they default based on success
    status    = json.optString("status", if (json.getBoolean("success")) "success" else "failed")
)