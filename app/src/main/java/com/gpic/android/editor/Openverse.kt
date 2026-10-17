package com.gpic.android.editor

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A song from Openverse (Creative Commons audio from Jamendo, Freesound, ccMixter, …). */
data class OpenTrack(
    val id: String,
    val title: String,
    val creator: String,
    /** "by", "by-sa", "cc0", "pdm", … */
    val license: String,
    val licenseVersion: String,
    val licenseUrl: String,
    val url: String,
    val landingUrl: String,
    val durationMs: Long,
    val source: String,
    val genres: List<String>,
    /** Openverse's ready-made attribution sentence. */
    val attribution: String,
) {
    val licenseLabel: String get() = when (license) {
        "cc0" -> "CC0"
        "pdm" -> "Public domain"
        else -> "CC ${license.uppercase()} $licenseVersion".trim()
    }
    /** CC0 / public domain don't need credit; everything else does. */
    val needsCredit: Boolean get() = license !in setOf("cc0", "pdm")
}

/**
 * Searches Openverse's audio API anonymously (no key; ~20 requests a minute and 200 a day per phone), so results
 * are cached for a day and searches run only when the user submits one. Only licenses that allow use in an edited
 * video are asked for (commercial + modification: CC BY, BY-SA, CC0, public domain), and ND/NC results are dropped
 * again here in case the filter changes. Tracks are downloaded into filesDir/music and checked to really be audio.
 */
class Openverse(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val cacheDir = File(context.cacheDir, "openverse").apply { mkdirs() }
    val musicDir = File(context.filesDir, "music").apply { mkdirs() }

    class QuotaException(msg: String) : IOException(msg)

    fun search(query: String, page: Int = 1): List<OpenTrack> {
        val q = query.trim().ifEmpty { "music" }
        val key = "${q.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(60)}_p$page.json"
        val cached = File(cacheDir, key)
        if (cached.exists() && System.currentTimeMillis() - cached.lastModified() < CACHE_MS) {
            runCatching { return parse(JSONObject(cached.readText())) }
        }
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "20")
            .addQueryParameter("license_type", "commercial,modification")
            .addQueryParameter("category", "music")
            .addQueryParameter("mature", "false")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.code == 429 -> throw QuotaException("Openverse's free search limit is used up for now (about 200 searches a day). Try again later, or pick music from the phone.")
                !r.isSuccessful -> throw IOException("Openverse search failed (HTTP ${r.code})")
            }
            cached.writeText(body)
            return parse(JSONObject(body))
        }
    }

    private fun parse(o: JSONObject): List<OpenTrack> {
        val arr = o.optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val license = t.optString("license").lowercase()
            if ("nd" in license || "nc" in license || license.isEmpty()) return@mapNotNull null
            val url = t.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            OpenTrack(
                id = t.optString("id"),
                title = t.optString("title").ifBlank { "Untitled" },
                creator = t.optString("creator").ifBlank { "Unknown artist" },
                license = license,
                licenseVersion = t.optString("license_version"),
                licenseUrl = t.optString("license_url"),
                url = url,
                landingUrl = t.optString("foreign_landing_url"),
                durationMs = t.optLong("duration", 0),
                source = t.optString("source"),
                genres = t.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty(),
                attribution = t.optString("attribution").ifBlank { "\"${t.optString("title")}\" by ${t.optString("creator")} ($license)" },
            )
        }
    }

    /** The downloaded file for [t], fetching it first if needed; [onProgress] gets 0..100 (or -1 when unknown). */
    fun download(t: OpenTrack, onProgress: (Int) -> Unit): File {
        musicDir.listFiles { f -> f.nameWithoutExtension == safe(t.id) }?.firstOrNull()?.let { return it }
        val tmp = File(musicDir, "${safe(t.id)}.part")
        val req = Request.Builder().url(t.url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            if (total > MAX_BYTES) throw IOException("That track is too big (${total / 1_000_000} MB)")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_BYTES) throw IOException("That track is too big")
                        val p = if (total > 0) (done * 100 / total).toInt() else -1
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
        }
        // Jamendo reports "mp32"; trust the bytes, not the label.
        val ext = AudioSniff.extension(tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } })
        if (ext == null) { tmp.delete(); throw IOException("The download isn't an audio file Android can play") }
        val file = File(musicDir, "${safe(t.id)}.$ext")
        tmp.renameTo(file)
        // Remember the credit next to the file, for the music list's "Downloaded" section.
        File(musicDir, "${safe(t.id)}.json").writeText(toJson(t).toString())
        return file
    }

    /** Tracks already downloaded (newest first), with their credits. */
    fun downloaded(): List<Pair<OpenTrack, File>> =
        musicDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }.mapNotNull { j ->
            val t = runCatching { fromJson(JSONObject(j.readText())) }.getOrNull() ?: return@mapNotNull null
            val audio = musicDir.listFiles { f -> f.nameWithoutExtension == j.nameWithoutExtension && f.extension !in setOf("json", "part") }?.firstOrNull()
                ?: return@mapNotNull null
            t to audio
        }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9-]"), "_").take(64)

    private fun toJson(t: OpenTrack) = JSONObject().put("id", t.id).put("title", t.title).put("creator", t.creator)
        .put("license", t.license).put("license_version", t.licenseVersion).put("license_url", t.licenseUrl)
        .put("url", t.url).put("landing", t.landingUrl).put("duration", t.durationMs).put("source", t.source)
        .put("genres", JSONArray(t.genres)).put("attribution", t.attribution)

    private fun fromJson(o: JSONObject) = OpenTrack(
        o.getString("id"), o.optString("title"), o.optString("creator"), o.optString("license"), o.optString("license_version"),
        o.optString("license_url"), o.optString("url"), o.optString("landing"), o.optLong("duration"), o.optString("source"),
        o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty(), o.optString("attribution"),
    )

    companion object {
        private const val API = "https://api.openverse.org/v1/audio/"
        private const val USER_AGENT = "GPic-Android/1 (video editor music search; https://github.com/debakarr/gpic-android)"
        private const val CACHE_MS = 24 * 3600_000L
        private const val MAX_BYTES = 40L * 1024 * 1024
        /** Moods offered as one-tap searches. */
        val MOODS = listOf("Upbeat", "Chill", "Cinematic", "Epic", "Travel", "Happy", "Lo-fi", "Acoustic", "Electronic", "Ambient", "Rock", "Piano")
    }
}

/** What kind of audio file some bytes start with. Pure. */
object AudioSniff {
    fun extension(head: ByteArray): String? {
        fun at(i: Int, s: String) = head.size >= i + s.length && s.indices.all { head[i + it] == s[it].code.toByte() }
        return when {
            at(0, "ID3") -> "mp3"
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            at(0, "OggS") -> "ogg"
            at(0, "fLaC") -> "flac"
            at(0, "RIFF") && at(8, "WAVE") -> "wav"
            at(4, "ftyp") -> "m4a"
            else -> null
        }
    }
}
