package com.gpic.android.data.cache

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Also files Google Photos refused ([REJECTED]), so a re-run doesn't send them again.
 *
 * Files Google Photos has confirmed it has (uploaded now, or an exact hash match), keyed by the list key
 * (Uri / path) and remembered with their size and modified time. A re-run of the same folder (a WhatsApp
 * folder checked every week) skips those without reading or asking again. An entry only counts while the
 * file is unchanged and for [MAX_AGE_MS]; callers don't use it when they're about to delete originals.
 */
class CheckedCache(context: Context) {
    private val file = File(context.filesDir, "gpic_checked.json")
    private val entries: MutableMap<String, Entry> = load()
    private var unsaved = 0

    private data class Entry(val size: Long, val modified: Long, val mediaKey: String, val checkedAt: Long)

    /** The media key if [key] was confirmed in the library with this exact size and modified time. */
    @Synchronized
    fun find(key: String, size: Long, modified: Long, now: Long = System.currentTimeMillis()): String? {
        val e = entries[key] ?: return null
        if (modified <= 0 || e.size != size || e.modified != modified || now - e.checkedAt > MAX_AGE_MS) return null
        return e.mediaKey
    }

    /** Google Photos refused this exact file before; [find] then returns [REJECTED]. */
    fun recordRejected(key: String, size: Long, modified: Long) = record(key, size, modified, REJECTED)

    @Synchronized
    fun record(key: String, size: Long, modified: Long, mediaKey: String) {
        if (modified <= 0 || mediaKey.isEmpty()) return // no way to notice a changed file
        entries[key] = Entry(size, modified, mediaKey, System.currentTimeMillis())
        if (++unsaved >= 100) flush()
    }

    @Synchronized
    fun flush() {
        if (unsaved == 0) return
        unsaved = 0
        val now = System.currentTimeMillis()
        entries.values.removeAll { now - it.checkedAt > MAX_AGE_MS }
        val o = JSONObject()
        entries.forEach { (k, e) ->
            o.put(k, JSONObject().put("s", e.size).put("m", e.modified).put("k", e.mediaKey).put("t", e.checkedAt))
        }
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    private fun load(): MutableMap<String, Entry> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWithTo(HashMap()) { k ->
            val e = o.getJSONObject(k)
            Entry(e.getLong("s"), e.getLong("m"), e.getString("k"), e.getLong("t"))
        }
    }.getOrElse { HashMap() }

    companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        /** Stored in place of a media key for files Google Photos refused. */
        const val REJECTED = "!rejected"
    }
}
