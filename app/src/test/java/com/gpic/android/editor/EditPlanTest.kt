package com.gpic.android.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPlanTest {
    private val luts = setOf("dji", LutStore.VIVID_ID)

    @Test fun segmentsAreClampedSortedMergedAndSliversDropped() {
        val p = EditPlan(segments = listOf(Segment(8_000, 12_000), Segment(-500, 2_000), Segment(1_500, 3_000), Segment(5_000, 5_100), Segment(9_000, 99_000)))
            .sanitized(20_000, luts)
        assertEquals(listOf(Segment(0, 3_000), Segment(8_000, 20_000)), p.segments)
        assertEquals(15_000, p.keptMs(20_000))
    }

    @Test fun wholeVideoRangeMeansNoSegments() {
        assertTrue(EditPlan(segments = listOf(Segment(0, 10_000))).sanitized(10_000, luts).segments.isEmpty())
    }

    @Test fun numbersAreClampedAndUnknownLutDropped() {
        val p = EditPlan(speed = 9f, lutId = "made-up", brightness = 3f, saturation = -400f, originalVolume = Float.NaN, maxHeight = 480, rotation = 45)
            .sanitized(10_000, luts)
        assertEquals(EditPlan.MAX_SPEED, p.speed)
        assertNull(p.lutId)
        assertEquals(0.5f, p.brightness)
        assertEquals(-50f, p.saturation)
        assertEquals(1f, p.originalVolume)
        assertEquals(0, p.maxHeight)
        assertEquals(0, p.rotation)
        assertEquals(2_500, EditPlan(speed = 4f).outputMs(10_000))
    }

    @Test fun jsonRoundTripAndClaudeShape() {
        val plan = EditPlan(segments = listOf(Segment(1_000, 4_500)), speed = 2f, lutId = "dji", aspect = Aspect.VERTICAL, maxHeight = 720, musicStartMs = 12_000)
        assertEquals(plan, EditPlan.fromJson(plan.toJson()))
        // What a Claude reply looks like: seconds, "none" for no LUT, extra explanation field.
        val claude = JSONObject("""{"explanation":"x","segments":[{"start_s":2.5,"end_s":6}],"speed":1,"lut":"none",
            "brightness":0.1,"contrast":0,"saturation":10,"keep_original_audio":false,"original_volume":1,"music_volume":0.5,
            "music_start_s":0,"aspect":"SQUARE","max_height":0,"rotation":90}""")
        val p = EditPlan.fromJson(claude, base = plan).sanitized(10_000, luts)
        assertEquals(listOf(Segment(2_500, 6_000)), p.segments)
        assertNull(p.lutId)
        assertEquals(false, p.keepOriginalAudio)
        assertEquals(Aspect.SQUARE, p.aspect)
        assertEquals(90, p.rotation)
    }

    @Test fun schemaIsStrict() {
        val s = EditPlan.schema(listOf("dji"))
        assertEquals(false, s.getBoolean("additionalProperties"))
        val required = s.getJSONArray("required")
        val props = s.getJSONObject("properties")
        assertEquals(props.length(), required.length())
        assertTrue(props.has("explanation"))
        assertEquals(listOf("dji", "none"), props.getJSONObject("lut").getJSONArray("enum").let { a -> (0 until a.length()).map { a.getString(it) } })
    }
}
