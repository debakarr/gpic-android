package com.gpic.android.data.cache

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Files this install has already uploaded, keyed by name + size → media key.
 *
 * Lets single-pass sources (camera over WiFi) skip a re-run without reading every file twice to
 * hash it first. Lives in filesDir (not cache) so it survives cache clears; "Force re-upload"
 * bypasses it.
 */
class UploadHistory(context: Context) {
    private val file = File(context.filesDir, "gpic_upload_history.json")
    private val entries: MutableMap<String, String> = load()

    private fun id(name: String, size: Long) = "$name|$size"

    @Synchronized
    fun find(name: String, size: Long): String? = entries[id(name, size)]

    private var unsaved = 0

    /** Rewriting the whole file per upload was quadratic over big batches; it's written every 50 and on [flush]. */
    @Synchronized
    fun record(name: String, size: Long, mediaKey: String) {
        entries[id(name, size)] = mediaKey
        if (++unsaved >= 50) flush()
    }

    @Synchronized
    fun flush() {
        if (unsaved == 0) return
        unsaved = 0
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject(entries as Map<*, *>).toString())
            tmp.renameTo(file)
        }
    }

    private fun load(): MutableMap<String, String> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }.getOrElse { mutableMapOf() }
}
