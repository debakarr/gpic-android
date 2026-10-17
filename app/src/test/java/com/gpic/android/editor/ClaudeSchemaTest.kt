package com.gpic.android.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSchemaTest {
    /** Strict structured output: every object lists all its properties as required and allows no others. */
    private fun checkStrict(o: JSONObject, path: String = "$") {
        if (o.optString("type") == "object") {
            assertEquals("$path additionalProperties", false, o.getBoolean("additionalProperties"))
            val props = o.getJSONObject("properties")
            val required = o.getJSONArray("required").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            assertEquals("$path required", props.keys().asSequence().toSet(), required)
            props.keys().forEach { k -> checkStrict(props.getJSONObject(k), "$path.$k") }
        }
        o.optJSONObject("items")?.let { checkStrict(it, "$path[]") }
    }

    @Test fun schemaIsStrictEverywhere() {
        val s = ClaudeEditPlanner.schema(listOf("dji"))
        checkStrict(s)
        val props = s.getJSONObject("properties")
        assertTrue(props.has("explanation"))
        val lut = props.getJSONObject("look").getJSONObject("properties").getJSONObject("lut").getJSONArray("enum")
        assertEquals(listOf("dji", "none"), (0 until lut.length()).map { lut.getString(it) })
        val transitions = props.getJSONObject("segments").getJSONObject("items").getJSONObject("properties").getJSONObject("transition").getJSONArray("enum")
        assertEquals(Transition.entries.size, transitions.length())
    }
}
