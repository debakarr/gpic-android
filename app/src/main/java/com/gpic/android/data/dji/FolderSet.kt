package com.gpic.android.data.dji

/**
 * Several picked folders (SAF trees) used together. Pure (strings only), so it's unit-tested.
 *
 * A folder is identified by its provider authority plus tree document id. Phone storage ids are paths
 * ("primary:DCIM/Camera"), so nesting can be seen up front; a USB camera's MTP ids are opaque numbers, so
 * overlap there is only caught by [dedupe] after scanning.
 */
object FolderSet {
    data class Folder(val authority: String, val docId: String)

    /** True when [child] is [parent] or lies inside it. Only path-like ids ("volume:path") can nest. */
    fun contains(parent: Folder, child: Folder): Boolean {
        if (parent.authority != child.authority) return false
        if (parent.docId == child.docId) return true
        if (':' !in parent.docId || ':' !in child.docId) return false
        val p = parent.docId.trimEnd('/')
        // "primary:" is the whole volume; otherwise the child must continue past a '/'.
        return if (p.endsWith(':')) child.docId.startsWith(p) else child.docId.startsWith("$p/")
    }

    /** What adding a folder does: the new list, plus a note when something was already covered. */
    data class AddResult<T>(val folders: List<T>, val added: Boolean, val replaced: List<T>, val coveredBy: T?)

    /**
     * Adds [new] to [existing]. When subfolders are scanned ([recursive]), a folder inside an existing one adds
     * nothing ([AddResult.coveredBy]) and existing folders inside the new one are dropped ([AddResult.replaced]).
     * Without subfolders, only the very same folder counts as a duplicate.
     */
    fun <T> add(existing: List<T>, new: T, recursive: Boolean, id: (T) -> Folder): AddResult<T> {
        val n = id(new)
        val covering = existing.firstOrNull { e -> if (recursive) contains(id(e), n) else id(e) == n }
        if (covering != null) return AddResult(existing, added = false, replaced = emptyList(), coveredBy = covering)
        val inside = if (recursive) existing.filter { contains(n, id(it)) } else emptyList()
        return AddResult(existing - inside.toSet() + new, added = true, replaced = inside, coveredBy = null)
    }

    /** Keeps the first of each item with the same [key] (one file reached through two overlapping folders). */
    fun <T> dedupe(items: List<T>, key: (T) -> String): List<T> {
        val seen = HashSet<String>()
        return items.filter { seen.add(key(it)) }
    }
}
