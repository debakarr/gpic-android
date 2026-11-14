package com.gpic.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CameraStagerTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A cancelled transfer leaves whole files and a part of the one it was on; both are counted and cleared. */
    @Test fun clearAllCountsAndDeletesCompleteAndPartialCopies() {
        val dir = tmp.newFolder("camera-staging")
        File(dir, "DJI_0001.MP4").writeBytes(ByteArray(1500))
        File(dir, "DJI_0002.MP4").writeBytes(ByteArray(500))
        val stager = CameraStager(dir)

        assertEquals(2, stager.filesOnDisk().size)
        assertEquals(2000L, stager.bytesOnDisk())
        assertEquals(2000L, stager.clearAll())
        assertEquals(0L, stager.bytesOnDisk())
        assertTrue(stager.filesOnDisk().isEmpty())
    }

    @Test fun missingDirectoryIsEmptyAndClearsNothing() {
        val stager = CameraStager(File(tmp.root, "never-created"))
        assertEquals(0L, stager.bytesOnDisk())
        assertEquals(0L, stager.clearAll())
    }
}
