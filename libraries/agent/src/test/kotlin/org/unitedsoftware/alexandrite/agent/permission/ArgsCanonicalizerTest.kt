package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ArgsCanonicalizerTest {
    private fun canonical(json: String): String = ArgsCanonicalizer.canonical(Json.parseToJsonElement(json))

    @Test
    fun `key order and whitespace do not change the canonical form`() {
        val a = """{"path": "notes/a.txt", "content": "hi", "opts": {"z": 1, "a": [ {"y":2,"b":3} ]}}"""
        val b = """{ "opts":{"a":[{"b":3,"y":2}],"z":1},"content":"hi",
            "path":"notes/a.txt" }"""

        assertEquals("""{"content":"hi","opts":{"a":[{"b":3,"y":2}],"z":1},"path":"notes/a.txt"}""", canonical(a))
        assertEquals(canonical(a), canonical(b))
    }

    @Test
    fun `array order and literal values are kept`() {
        assertNotEquals(canonical("""{"args":["-l","/tmp"]}"""), canonical("""{"args":["/tmp","-l"]}"""))
        assertEquals("""{"a":1.50,"b":true,"c":null}""", canonical("""{"c": null, "b": true, "a": 1.50}"""))
    }

    @Test
    fun `paths are not resolved`() {
        assertNotEquals(canonical("""{"path":"a/../b.txt"}"""), canonical("""{"path":"b.txt"}"""))
    }

    @Test
    fun `a string keeps its JSON escapes, and characters JSON need not escape stay as they are`() {
        assertEquals(
            "{\"text\":\"line\\nnext \\\"quoted\\\" \\u0001 \u202E\"}",
            canonical("{\"text\": \"line\\nnext \\\"quoted\\\" \\u0001 \u202E\"}"),
        )
    }
}
