package com.gpic.android.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClaudeReplyTest {
    private val durations = listOf(10_000L, 6_000L)
    private val current = Timeline.materialize(EditPlan(look = Look(filter = Filter.WARM)), durations)

    @Test fun readsAFencedBlockAmongProse() {
        val reply = """
            Sure! Here's a punchy edit:

            ```json
            {"explanation": "Kept the jump and slowed it down.",
             "segments": [{"clip": 1, "start_s": 1, "end_s": 4, "speed": 0.5, "curve": "NONE", "volume": 1, "transition": "ZOOM_IN", "motion": "NONE"},
                          {"clip": 0, "start_s": 2, "end_s": 5, "speed": 1, "curve": "HERO", "volume": 0.5, "transition": "NONE", "motion": "NONE"}],
             "texts": [{"text": "Goa 2026", "start_s": 0, "end_s": 2}]}
            ```
            Let me know if you want it longer.
        """.trimIndent()
        val r = ClaudeEditPlanner.parseReply(reply, current, durations, emptySet())
        assertEquals(listOf(1, 0), r.plan.segments.map { it.clip })
        assertEquals(0.5f, r.plan.segments[0].speed)
        assertEquals(SpeedCurve.HERO, r.plan.segments[1].curve)
        assertEquals("Goa 2026", r.plan.texts.single().text)
        // Fields it left out stay as they were.
        assertEquals(Filter.WARM, r.plan.look.filter)
        assertEquals("Kept the jump and slowed it down.", r.explanation)
    }

    @Test fun readsBareJsonAndKeepsWhatItDidntMention() {
        val r = ClaudeEditPlanner.parseReply("Here: {\"aspect\": \"VERTICAL\", \"canvas\": \"BLUR\"} done", current, durations, emptySet())
        assertEquals(Aspect.VERTICAL, r.plan.aspect)
        assertEquals(Canvas.BLUR, r.plan.canvas)
        assertEquals(current.segments.map { it.clip to it.endMs }, r.plan.segments.map { it.clip to it.endMs })
        assertTrue(r.explanation.isNotBlank())
    }

    @Test fun bracesInsideStringsDontConfuseIt() {
        assertEquals("{\"explanation\": \"a } b\", \"x\": 1}", ClaudeEditPlanner.extractJson("ok {\"explanation\": \"a } b\", \"x\": 1} end"))
    }

    @Test fun brokenOrMissingJsonIsAClearError() {
        for (bad in listOf("no json here", "```json\n{\"segments\": [1, 2,],}\n```")) {
            try {
                ClaudeEditPlanner.parseReply(bad, current, durations, emptySet())
                if (bad == "no json here") fail("should have failed")
            } catch (e: ClaudeException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test fun promptListsTheFieldsAndRequest() {
        // (VideoInfo needs an android.net.Uri, which the JVM stubs can't make; the clip lines are covered on the device.)
        val p = ClaudeEditPlanner.chatPrompt("make it short", emptyList(), current, emptyList(), withStoryboard = true)
        assertTrue(p.contains("```json"))
        assertTrue(p.contains("transition (NONE|FADE_BLACK"))
        assertTrue(p.contains("My request: make it short"))
        assertTrue(p.contains("attached image"))
    }
}
