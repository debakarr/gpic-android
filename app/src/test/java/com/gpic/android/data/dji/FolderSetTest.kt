package com.gpic.android.data.dji

import com.gpic.android.data.dji.FolderSet.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSetTest {
    private val ext = "com.android.externalstorage.documents"
    private fun f(id: String, auth: String = ext) = Folder(auth, id)

    @Test fun nestedPathsAreContained() {
        assertTrue(FolderSet.contains(f("primary:DCIM"), f("primary:DCIM/Camera")))
        assertTrue(FolderSet.contains(f("primary:DCIM"), f("primary:DCIM")))
        assertTrue(FolderSet.contains(f("primary:"), f("primary:Pictures/WhatsApp")))
        // A sibling that merely starts with the same letters isn't inside.
        assertFalse(FolderSet.contains(f("primary:DCIM"), f("primary:DCIM2")))
        assertFalse(FolderSet.contains(f("primary:DCIM/Camera"), f("primary:DCIM")))
        // Other volume / other provider.
        assertFalse(FolderSet.contains(f("primary:DCIM"), f("1234-5678:DCIM/Camera")))
        assertFalse(FolderSet.contains(f("primary:DCIM"), f("primary:DCIM/Camera", auth = "mtp")))
    }

    @Test fun opaqueIdsOnlyMatchThemselves() {
        assertTrue(FolderSet.contains(f("65537", "mtp"), f("65537", "mtp")))
        assertFalse(FolderSet.contains(f("65537", "mtp"), f("65538", "mtp")))
    }

    @Test fun addingASubfolderOfAPickedFolderAddsNothing() {
        val r = FolderSet.add(listOf(f("primary:DCIM")), f("primary:DCIM/Camera"), recursive = true) { it }
        assertFalse(r.added)
        assertEquals(f("primary:DCIM"), r.coveredBy)
        assertEquals(listOf(f("primary:DCIM")), r.folders)
    }

    @Test fun addingAParentReplacesItsSubfolders() {
        val existing = listOf(f("primary:DCIM/Camera"), f("primary:Pictures"), f("primary:DCIM/Screenshots"))
        val r = FolderSet.add(existing, f("primary:DCIM"), recursive = true) { it }
        assertTrue(r.added)
        assertEquals(listOf(f("primary:DCIM/Camera"), f("primary:DCIM/Screenshots")), r.replaced)
        assertEquals(listOf(f("primary:Pictures"), f("primary:DCIM")), r.folders)
    }

    @Test fun withoutSubfoldersParentAndChildAreDifferentFolders() {
        val r = FolderSet.add(listOf(f("primary:DCIM")), f("primary:DCIM/Camera"), recursive = false) { it }
        assertTrue(r.added)
        assertNull(r.coveredBy)
        assertEquals(2, r.folders.size)
        assertFalse(FolderSet.add(r.folders, f("primary:DCIM"), recursive = false) { it }.added)
    }

    @Test fun dedupeKeepsFirstOccurrence() {
        val files = listOf("a" to 1, "b" to 2, "a" to 3, "c" to 4, "b" to 5)
        assertEquals(listOf("a" to 1, "b" to 2, "c" to 4), FolderSet.dedupe(files) { it.first })
    }
}
