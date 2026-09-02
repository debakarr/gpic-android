package com.gpic.android.data.cache

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Port of gphotos/upload_cache.py UploadCache
 * Disk-based cache for resumable upload sessions.
 * TTL 24h – Google upload IDs expire.
 */
class UploadCache(context: Context) {
    private val file = File(context.cacheDir, "gpic_upload_cache.json")
    private var cache: MutableMap<String, JSONObject> = mutableMapOf()

    companion object {
        const val TTL_SECONDS = 86400L // 24h
    }

    init { load() }

    @Synchronized
    fun get(filePath: String): JSONObject? {
        val entry = cache[filePath] ?: return null
        val ts = entry.optLong("timestamp", 0)
        if (System.currentTimeMillis() / 1000 - ts > TTL_SECONDS) {
            cache.remove(filePath)
            save()
            return null
        }
        return entry
    }

    @Synchronized
    fun set(filePath: String, uploadId: String, fileSize: Long) {
        cache[filePath] = JSONObject().apply {
            put("upload_id", uploadId)
            put("file_size", fileSize)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        save()
    }

    @Synchronized
    fun remove(filePath: String) {
        cache.remove(filePath)
        save()
    }

    @Synchronized
    fun clearAll() {
        cache.clear()
        save()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val obj = JSONObject(file.readText())
            for (key in obj.keys()) {
                cache[key] = obj.getJSONObject(key)
            }
        } catch (e: Exception) {
            cache = mutableMapOf()
        }
    }

    private fun save() {
        try {
            val obj = JSONObject()
            cache.forEach { (k, v) -> obj.put(k, v) }
            file.parentFile?.mkdirs()
            file.writeText(obj.toString())
        } catch (e: Exception) {
            // ignore
        }
    }
}
