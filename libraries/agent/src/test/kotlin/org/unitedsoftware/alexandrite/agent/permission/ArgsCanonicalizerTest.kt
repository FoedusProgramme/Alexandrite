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
    fun `array order is kept`() {
        assertNotEquals(canonical("""{"args":["-l","/tmp"]}"""), canonical("""{"args":["/tmp","-l"]}"""))
    }
}
