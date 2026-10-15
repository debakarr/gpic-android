package com.gpic.android.data.albums

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What GPic knows is in the library: media key + capture time/GPS of every file it uploaded or found
 * already there. Album suggestions are computed from this on the phone; it never leaves the device
 * (filesDir, and backups are off).
 */
class MediaIndex private constructor(context: Context) {
    private val file = File(context.filesDir, "gpic_media_index.json")
    private val items: MutableMap<String, IndexedMedia>
    private val dismissed: MutableSet<String>
    private var dirty = 0

    private val _version = MutableStateFlow(0)
    /** Bumps on every change so screens can recompute. */
    val version: StateFlow<Int> = _version

    init {
        val (i, d) = load()
        items = i
        dismissed = d
    }

    @Synchronized fun all(): List<IndexedMedia> = items.values.toList()
    @Synchronized fun dismissedIds(): Set<String> = dismissed.toSet()
    @Synchronized fun size(): Int = items.size

    /** Add or refresh one item (keeps an album it was already put in). Written in batches; call [flush]. */
    @Synchronized
    fun record(m: IndexedMedia) {
        val old = items[m.mediaKey]
        items[m.mediaKey] = m.copy(album = old?.album ?: m.album, localUri = m.localUri ?: old?.localUri)
        if (++dirty >= 50) flush() else _version.value++
    }

    @Synchronized
    fun markAlbum(keys: Collection<String>, album: String) {
        keys.forEach { k -> items[k]?.let { items[k] = it.copy(album = album) } }
        dirty++
        flush()
    }

    @Synchronized
    fun dismiss(id: String) {
        dismissed += id
        dirty++
        flush()
    }

    @Synchronized
    fun flush() {
        if (dirty == 0) return
        dirty = 0
        val arr = JSONArray()
        items.values.forEach { m ->
            arr.put(JSONObject().apply {
                put("k", m.mediaKey); put("n", m.name); put("t", m.takenMs)
                m.lat?.let { put("lat", it) }; m.lon?.let { put("lon", it) }
                m.localUri?.let { put("u", it) }; m.album?.let { put("a", it) }
            })
        }
        val root = JSONObject().put("items", arr).put("dismissed", JSONArray(dismissed.toList()))
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        }
        _version.value++
    }

    private fun load(): Pair<MutableMap<String, IndexedMedia>, MutableSet<String>> = runCatching {
        val root = JSONObject(file.readText())
        val arr = root.getJSONArray("items")
        val map = LinkedHashMap<String, IndexedMedia>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val m = IndexedMedia(
                mediaKey = o.getString("k"), name = o.optString("n"), takenMs = o.optLong("t"),
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lon = if (o.has("lon")) o.getDouble("lon") else null,
                localUri = o.optString("u").ifEmpty { null }, album = o.optString("a").ifEmpty { null },
            )
            map[m.mediaKey] = m
        }
        val d = root.optJSONArray("dismissed")
        map to (0 until (d?.length() ?: 0)).mapTo(mutableSetOf()) { d!!.getString(it) }
    }.getOrElse { LinkedHashMap<String, IndexedMedia>() to mutableSetOf() }

    companion object {
        @Volatile private var instance: MediaIndex? = null
        fun get(context: Context): MediaIndex =
            instance ?: synchronized(this) { instance ?: MediaIndex(context.applicationContext).also { instance = it } }
    }
}
