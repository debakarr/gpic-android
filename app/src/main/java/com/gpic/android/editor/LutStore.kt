package com.gpic.android.editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class LutInfo(
    val id: String,
    val title: String,
    /** What it's for, in words Claude can act on. */
    val description: String,
    val builtIn: Boolean,
)

/**
 * Colour LUTs for the editor: GPic's built-in approximate look plus `.cube` files the user imports
 * (kept in filesDir/luts). DJI's own D-Log M → Rec.709 LUT isn't bundled (DJI publishes it as a download,
 * not for redistribution); imported into its slot it becomes the default for log footage.
 */
class LutStore(private val context: Context) {
    private val dir = File(context.filesDir, "luts").apply { mkdirs() }
    private val cache = HashMap<String, CubeLut>()

    fun list(): List<LutInfo> {
        val imported = dir.listFiles { f -> f.name.endsWith(".cube") }.orEmpty()
            .map { f ->
                val id = f.nameWithoutExtension
                if (id == DJI_ID) LutInfo(id, DJI_TITLE, DJI_DESC, builtIn = false)
                else LutInfo(id, titleOf(f) ?: id, "Imported LUT \"${titleOf(f) ?: id}\"", builtIn = false)
            }
            .sortedWith(compareBy({ it.id != DJI_ID }, { it.title.lowercase() }))
        return imported + LutInfo(VIVID_ID, VIVID_TITLE, VIVID_DESC, builtIn = true)
    }

    val hasDjiLut: Boolean get() = File(dir, "$DJI_ID.cube").exists()

    /** The LUT to offer first for log footage: DJI's when imported, else GPic's approximation. */
    val defaultId: String get() = if (hasDjiLut) DJI_ID else VIVID_ID

    @Synchronized
    fun load(id: String): CubeLut = cache.getOrPut(id) {
        if (id == VIVID_ID) CubeLut.flatToVivid()
        else File(dir, "$id.cube").takeIf { it.exists() }?.let { CubeLut.parse(it.readText(), id) }
            ?: throw IllegalArgumentException("LUT $id is missing")
    }

    /**
     * Copies a `.cube` file in after checking it parses. [asDji] files it as the DJI D-Log M LUT.
     * Throws [IllegalArgumentException] with a readable reason for files that aren't 3D cube LUTs.
     */
    @Synchronized
    fun import(uri: Uri, asDji: Boolean): LutInfo {
        val name = displayName(uri) ?: "lut.cube"
        val text = context.contentResolver.openInputStream(uri)?.use { s ->
            val bytes = s.readNBytesCompat(MAX_FILE_BYTES + 1)
            require(bytes.size <= MAX_FILE_BYTES) { "That file is too big for a LUT" }
            String(bytes, Charsets.UTF_8)
        } ?: throw IllegalArgumentException("Couldn't read the file")
        val lut = CubeLut.parse(text, name.substringBeforeLast('.'))
        val id = if (asDji) DJI_ID else uniqueId(name.substringBeforeLast('.'))
        File(dir, "$id.cube").writeText(text)
        cache[id] = lut
        return list().first { it.id == id }
    }

    @Synchronized
    fun delete(id: String) {
        if (id == VIVID_ID) return
        File(dir, "$id.cube").delete()
        cache.remove(id)
    }

    private fun uniqueId(base: String): String {
        val clean = base.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifEmpty { "lut" }
        var id = if (clean == DJI_ID || clean == VIVID_ID) "${clean}_1" else clean
        var n = 2
        while (File(dir, "$id.cube").exists()) id = "${clean}_${n++}"
        return id
    }

    private fun titleOf(f: File): String? = runCatching {
        f.bufferedReader().useLines { lines ->
            lines.take(50).firstOrNull { it.trim().startsWith("TITLE", ignoreCase = true) }
                ?.substringAfter(' ')?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val r = read(buf, 0, minOf(buf.size, max - out.size()))
            if (r == -1) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    companion object {
        const val DJI_ID = "dji_dlogm_rec709"
        const val VIVID_ID = "gpic_flat_vivid"
        const val DJI_TITLE = "DJI D-Log M → Rec.709"
        private const val DJI_DESC = "DJI's official conversion for Osmo Action 4 footage shot in D-Log M (flat, greyish, " +
            "low-contrast). Wrong for footage shot in Normal colour, which already looks finished."
        private const val VIVID_TITLE = "Flat → vivid (GPic)"
        private const val VIVID_DESC = "GPic's approximate look for flat/log footage (adds contrast and saturation). " +
            "Use for D-Log M clips only when the DJI LUT isn't installed."
        /** DJI's download page for the Action 4, where its D-Log M LUT is listed under software. */
        const val DJI_DOWNLOADS = "https://www.dji.com/osmo-action-4/downloads"
        private const val MAX_FILE_BYTES = 20 * 1024 * 1024
    }
}
