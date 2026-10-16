package com.gpic.android.data.upload

/**
 * Does a file's start match its extension? Google Photos happily takes random bytes named `.webp` and makes
 * a broken item of them, so GPic checks the formats whose signatures are certain before sending anything.
 * RAW formats and rarer video containers vary too much by maker and aren't checked (never a false "not media").
 */
object MediaSniff {
    /** Bytes [mismatch] needs from the start of the file. */
    const val HEAD_BYTES = 16

    /** Null when [head] fits the extension of [name] (or the format isn't checked), else a reason for the user. */
    fun mismatch(name: String, head: ByteArray): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val ok = when (ext) {
            "jpg", "jpeg" -> starts(head, 0xFF, 0xD8, 0xFF)
            "png" -> starts(head, 0x89, 'P'.code, 'N'.code, 'G'.code)
            "gif" -> ascii(head, 0, "GIF8")
            "webp" -> ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")
            "bmp" -> ascii(head, 0, "BM")
            // ISO base media: [size]["ftyp"]. Older QuickTime files can open with other atoms.
            "heic", "heif", "avif" -> ascii(head, 4, "ftyp")
            "mp4", "m4v", "3gp", "3g2" -> ascii(head, 4, "ftyp")
            "mov" -> listOf("ftyp", "moov", "mdat", "wide", "free", "skip", "pnot").any { ascii(head, 4, it) }
            else -> return null
        }
        return if (ok) null else "Not a real photo or video: its contents don't match .$ext"
    }

    private fun starts(b: ByteArray, vararg v: Int) = b.size >= v.size && v.indices.all { (b[it].toInt() and 0xFF) == v[it] }

    private fun ascii(b: ByteArray, at: Int, s: String) =
        b.size >= at + s.length && s.indices.all { b[at + it] == s[it].code.toByte() }
}
