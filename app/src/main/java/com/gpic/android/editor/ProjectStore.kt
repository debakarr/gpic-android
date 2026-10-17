package com.gpic.android.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved edit, as the project list shows it. */
data class ProjectSummary(val id: String, val name: String, val updatedMs: Long, val clipCount: Int, val durationMs: Long, val cover: File?)

/**
 * Drafts: every edit is saved as filesDir/projects/<id>/project.json (clip Uris + the plan) as it changes, with a
 * cover frame. Clips that came from Google Photos (whose read grant ends with the picker) and audio taken from a
 * clip are copied into the project's own media folder, so a draft reopens later; deleting the project deletes them.
 */
class ProjectStore(private val context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun newId(): String = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())

    fun mediaDir(id: String) = File(File(root, id), "media").apply { mkdirs() }

    fun save(id: String, name: String, clips: List<VideoInfo>, plan: EditPlan, durationMs: Long) {
        val dir = File(root, id).apply { mkdirs() }
        val o = JSONObject()
            .put("name", name)
            .put("updated", System.currentTimeMillis())
            .put("duration_ms", durationMs)
            .put("clips", JSONArray(clips.map { c ->
                JSONObject().put("uri", c.uri.toString()).put("name", c.name).put("image", c.isImage)
                    .put("proxy", c.proxy?.toString() ?: JSONObject.NULL).put("proxy_ms", c.proxyDurationMs)
            }))
            .put("plan", plan.toJson())
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "project.json"))
    }

    fun saveCover(id: String, bmp: Bitmap) {
        runCatching { File(File(root, id).apply { mkdirs() }, "cover.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
    }

    class LoadedClip(val uri: Uri, val name: String, val proxy: Uri?, val proxyMs: Long)
    class Loaded(val id: String, val name: String, val clips: List<LoadedClip>, val plan: EditPlan)

    fun load(id: String): Loaded? = runCatching {
        val o = JSONObject(File(File(root, id), "project.json").readText())
        val clips = o.getJSONArray("clips").let { a ->
            (0 until a.length()).map {
                a.getJSONObject(it).let { c ->
                    // An edit copy that was deleted (or never finished) is simply remade.
                    val proxy = c.optString("proxy").takeIf { p -> p.isNotEmpty() && p != "null" }?.let(Uri::parse)
                        ?.takeIf { p -> p.path?.let { path -> File(path).exists() } == true }
                    LoadedClip(Uri.parse(c.getString("uri")), c.optString("name"), proxy, c.optLong("proxy_ms"))
                }
            }
        }
        Loaded(id, o.optString("name"), clips, EditPlan.fromJson(o.getJSONObject("plan")))
    }.getOrNull()

    fun list(): List<ProjectSummary> = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
        val f = File(d, "project.json")
        if (!f.exists()) return@mapNotNull null
        runCatching {
            val o = JSONObject(f.readText())
            ProjectSummary(d.name, o.optString("name"), o.optLong("updated"), o.optJSONArray("clips")?.length() ?: 0,
                o.optLong("duration_ms"), File(d, "cover.jpg").takeIf { it.exists() })
        }.getOrNull()
    }.sortedByDescending { it.updatedMs }

    fun rename(id: String, name: String) {
        val f = File(File(root, id), "project.json")
        runCatching { val o = JSONObject(f.readText()).put("name", name); f.writeText(o.toString()) }
    }

    fun delete(id: String) { File(root, id).deleteRecursively() }

    /** True when [uri] is a file inside a project's media folder (so it's ours to delete). */
    fun isProjectMedia(uri: Uri) = uri.scheme == "file" && uri.path?.startsWith(root.absolutePath) == true
}
