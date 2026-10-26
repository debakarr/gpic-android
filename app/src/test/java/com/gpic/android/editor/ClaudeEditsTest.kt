package com.gpic.android.editor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeEditsTest {
    private val durations = listOf(20_000L, 8_000L)
    private val current = EditPlan(
        segments = listOf(Segment(0, 4_000, 0), Segment(5_000, 9_000, 0), Segment(1_000, 6_000, 1, rotation = 90)),
        texts = listOf(TextItem("Hello", 0, 2_000), TextItem("Bye", 9_000, 12_000)),
    )
    private val shots = listOf(
        ShotRef(0, 0, 6_000, peakMs = 3_000),
        ShotRef(0, 6_000, 20_000, peakMs = 19_500),
        ShotRef(1, 0, 1_200, peakMs = 600),
    )
    private val refs = EditRefs.of(current, shots)

    private fun run(vararg ops: String, plan: EditPlan = current, lutIds: Set<String> = emptySet()) =
        ClaudeEdits.apply(ops.map { JSONObject(it) }, plan, refs, durations, lutIds, "ok")

    @Test fun labelsStayOnTheirPartsWhenAnEarlierEditRemovesOne() {
        val r = run("""{"op":"remove_part","part":"P1"}""", """{"op":"set_part","part":"P3","speed":2}""")
        assertEquals(listOf(5_000L, 1_000L), r.plan.segments.map { it.startMs })
        assertEquals(listOf(1f, 2f), r.plan.segments.map { it.speed })
        assertTrue(r.problems.isEmpty())
    }

    @Test fun aRemovedOrUnknownLabelIsReportedNotGuessed() {
        val r = run("""{"op":"remove_part","part":"P2"}""", """{"op":"set_part","part":"P2","speed":3}""", """{"op":"remove_text","text_id":"T9"}""")
        assertEquals(2, r.plan.segments.size)
        assertTrue(r.plan.segments.all { it.speed == 1f })
        assertEquals(2, r.problems.size)
        assertTrue(r.problems[0], r.problems[0].startsWith("edit 2 (set_part P2): P2 is no longer"))
        assertTrue(r.problems[1].contains("there's no T9"))
        assertEquals(1, r.applied)
    }

    @Test fun montageCutsAroundThePeakAndStaysInsideTheShot() {
        val r = run("""{"op":"montage","shots":[{"shot":"S0","length_s":2},{"shot":"S1","length_s":3},{"shot":"S2","length_s":5,"transition":"zoom in"},"S0"]}""")
        val segs = r.plan.segments
        assertEquals(4, segs.size)
        assertEquals(2_000L to 4_000L, segs[0].startMs to segs[0].endMs) // centred on the 3 s peak
        assertEquals(17_000L to 20_000L, segs[1].startMs to segs[1].endMs) // peak near the end: pulled inside
        assertEquals(0L to 1_200L, segs[2].startMs to segs[2].endMs) // shot shorter than asked: the whole shot
        assertEquals(Transition.ZOOM_IN, segs[2].transition) // "zoom in" read as ZOOM_IN
        assertEquals(90, segs[2].rotation) // clip 1's rotation carried over
        assertEquals(ClaudeEdits.DEFAULT_PART_MS, segs[3].durationMs)
        assertTrue(r.problems.toString(), r.problems.isEmpty())
    }

    @Test fun photosInAMontageMoveUnlessClaudeSaysOtherwise() {
        val ops = """{"op":"montage","shots":[{"shot":"S2","length_s":2},{"shot":"S0","length_s":2},{"shot":"S2","length_s":2,"motion":"NONE"},{"shot":"S2","length_s":2}]}"""
        val r = ClaudeEdits.apply(listOf(JSONObject(ops)), current, refs, durations, emptySet(), "ok", photos = setOf(1))
        assertEquals(listOf(SmartEdit.PHOTO_MOVES[0], Motion.NONE, Motion.NONE, SmartEdit.PHOTO_MOVES[1]), r.plan.segments.map { it.motion })
    }

    @Test fun slowMotionTakesLessClipForTheSameLength() {
        val r = run("""{"op":"montage","shots":[{"shot":"S1","length_s":2,"speed":0.5}]}""")
        val s = r.plan.segments.single()
        assertEquals(1_000L, s.durationMs)
        assertEquals(2_000L, s.outputMs)
    }

    @Test fun badValuesAreSkippedWithAReasonAndTheRestApplies() {
        val r = run("""{"op":"set_part","part":"all","transition":"CROSSFADE","volume":0.5}""", """{"op":"wiggle"}""", """{"op":"montage","shots":[{"shot":"S7"},{"clip":5}]}""")
        assertTrue(r.plan.segments.all { it.volume == 0.5f && it.transition == Transition.NONE })
        assertTrue(r.problems[0].contains("transition \"CROSSFADE\" isn't one of"))
        assertTrue(r.problems[1].contains("unknown op"))
        assertTrue(r.problems.any { it.contains("there's no S7") })
        assertTrue(r.problems.any { it.contains("there's no clip 5") })
        assertEquals(3, r.plan.segments.size) // the failed montage left the track alone
    }

    @Test fun textsAndLookAndFormat() {
        val r = run(
            """{"op":"add_text","text":"Goa 2026","start_s":1,"style":"box","anim_in":"POP"}""",
            """{"op":"set_text","text_id":"T1","size":0.12,"y":0.2}""",
            """{"op":"look","filter":"warm","filter_amount":0.6,"contrast":0.2,"lut":"dlog"}""",
            """{"op":"format","aspect":"vertical","canvas":"BLUR"}""",
        )
        val added = r.plan.texts.last()
        assertEquals("Goa 2026", added.text)
        assertEquals(1_000L to 3_500L, added.startMs to added.endMs)
        assertEquals(TextStyle.BOX, added.style)
        assertEquals(TextAnim.POP, added.animIn)
        assertEquals(0.12f, r.plan.texts[0].size)
        assertEquals(current.texts[0].id, r.plan.texts[0].id)
        assertEquals(Filter.WARM, r.plan.look.filter)
        assertEquals(0.2f, r.plan.look.contrast)
        assertEquals(null, r.plan.look.lutId)
        assertTrue(r.problems.single().contains("no LUT \"dlog\""))
        assertEquals(Aspect.VERTICAL, r.plan.aspect)
        assertEquals(Canvas.BLUR, r.plan.canvas)
    }

    @Test fun musicTemplateAndBeatsAreFollowUps() {
        val r = run("""{"op":"find_music","query":"upbeat acoustic"}""", """{"op":"template","name":"Beat Drop"}""", """{"op":"cut_on_beats"}""",
            """{"op":"audio","music_volume":0.5,"original_volume":0.3}""")
        assertEquals(listOf(FollowUp.FindMusic("upbeat acoustic"), FollowUp.UseTemplate(VideoTemplate.BEAT_DROP), FollowUp.CutOnBeats), r.followUps)
        assertEquals(0.3f, r.plan.originalVolume)
        assertTrue(r.problems.toString(), r.problems.isEmpty()) // the music comes from find_music in the same reply
    }

    @Test fun repliesWithAnEmptyListChangeNothing() {
        val r = ClaudeEditPlanner.parseReply("```json\n{\"explanation\":\"Looks good.\",\"edits\":[]}\n```", current, refs, durations, emptySet())
        assertEquals(current.segments.map { it.startMs to it.endMs }, r.plan.segments.map { it.startMs to it.endMs })
        assertEquals("Looks good.", r.explanation)
        assertEquals(0, r.ops.size)
    }

    @Test fun opNamesAndLabelsAreReadLeniently() {
        val ops = JSONArray().put(JSONObject().put("do", "Remove_Part").put("part", 1)).put(JSONObject().put("type", "set_part").put("part", "#2").put("speed", "0.5"))
        val r = ClaudeEdits.apply((0 until ops.length()).map { ops.getJSONObject(it) }, current, refs, durations, emptySet(), "")
        assertEquals(listOf(5_000L, 1_000L), r.plan.segments.map { it.startMs })
        assertEquals(0.5f, r.plan.segments[0].speed)
    }

    @Test fun summaryShowsLabelsShotsAndOnlyWhatChanged() {
        val plan = current.copy(segments = current.segments.mapIndexed { i, s -> if (i == 1) s.copy(speed = 0.5f, transition = Transition.FADE_BLACK) else s })
        val text = ClaudeEdits.summary(plan, durations, shots)
        assertTrue(text, text.contains("P1: 0.0–4.0 s ← clip 0 0.0–4.0 s (S0)"))
        assertTrue(text, text.contains("P2: 4.0–12.0 s ← clip 0 5.0–9.0 s (S1), speed 0.5, transition FADE_BLACK"))
        assertTrue(text, text.contains("P3: 12.0–17.0 s ← clip 1 1.0–6.0 s, rotated 90"))
        assertTrue(text, text.contains("T2: 9.0–12.0 s \"Bye\""))
        assertTrue(text, text.contains("Look: natural"))
    }

    @Test fun labelsSurviveGPicBeingClosed() {
        // Drafts don't keep ids: a reloaded plan has new ones. The saved refs find the same parts and texts again.
        val saved = refs.toJson(current)
        val reloaded = current.copy(segments = current.segments.map { it.copy(id = newId()) }, texts = current.texts.map { it.copy(id = newId()) })
        val back = EditRefs.fromJson(JSONObject(saved.toString()), reloaded)
        val r = ClaudeEdits.apply(listOf(JSONObject("""{"op":"set_part","part":"P2","speed":0.5}"""), JSONObject("""{"op":"remove_text","text_id":"T1"}"""),
            JSONObject("""{"op":"montage","shots":["S1"]}""")), reloaded, back, durations, emptySet(), "")
        assertTrue(r.problems.toString(), r.problems.isEmpty())
        assertEquals(listOf("Bye"), r.plan.texts.map { it.text })
        assertEquals(17_500L to 20_000L, r.plan.segments.single().let { it.startMs to it.endMs })
    }

    @Test fun musicSettingsWithoutMusicAreReported() {
        val r = run("""{"op":"audio","music_volume":0.9}""")
        assertTrue(r.problems.single().contains("no music yet"))
    }

    @Test fun musicSettingsWaitForTheMusicTheSameReplyFinds() {
        val r = run("""{"op":"find_music","query":"upbeat"}""", """{"op":"audio","music_volume":0.9,"original_volume":0.3}""")
        assertTrue(r.problems.toString(), r.problems.isEmpty())
        assertEquals(listOf<FollowUp>(FollowUp.FindMusic("upbeat")), r.followUps)
    }
}
