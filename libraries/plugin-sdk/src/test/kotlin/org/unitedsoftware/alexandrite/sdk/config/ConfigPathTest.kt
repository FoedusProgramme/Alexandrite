package org.unitedsoftware.alexandrite.sdk.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigPathTest {
    @Test
    fun `plain keys join by dots`() {
        assertEquals(listOf("agent", "agents", "main", "model"), ConfigPath.parse("agent.agents.main.model"))
        assertEquals(listOf("channels", "telegram:work"), ConfigPath.parse("channels.telegram:work"))
    }

    @Test
    fun `a key in brackets may hold dots`() {
        val keys = listOf("providers", "openai-responses", "models", "gpt-6.1-sol", "contextWindow")

        assertEquals(keys, ConfigPath.parse("providers.openai-responses.models[\"gpt-6.1-sol\"].contextWindow"))
        assertEquals(keys, ConfigPath.parse("providers.openai-responses.models['gpt-6.1-sol'].contextWindow"))
        assertEquals(keys, ConfigPath.parse("providers.openai-responses.models.[\"gpt-6.1-sol\"].contextWindow"))
        assertEquals(listOf("a.b", "c"), ConfigPath.parse("[\"a.b\"].c"))
        assertEquals(listOf("x", "y.z"), ConfigPath.parse("x['y.z']"))
        assertEquals(listOf("a", "b", "c"), ConfigPath.parse("a[\"b\"][\"c\"]"))
    }

    @Test
    fun `escapes write quotes and backslashes in a quoted key`() {
        assertEquals(listOf("a", "say \"hi\""), ConfigPath.parse("a[\"say \\\"hi\\\"\"]"))
        assertEquals(listOf("a", "it's"), ConfigPath.parse("a['it\\'s']"))
        assertEquals(listOf("a", "back\\slash"), ConfigPath.parse("a[\"back\\\\slash\"]"))
        assertEquals(listOf("a", "it's"), ConfigPath.parse("a[\"it's\"]"))
    }

    @Test
    fun `a malformed path fails saying what is wrong`() {
        val cases = mapOf(
            "" to "it is empty",
            "a..b" to "a key is missing at 3",
            ".a" to "a key is missing at 1",
            "a." to "a key is missing at 3",
            "a[0]" to "the key in the bracket at 2 needs quotes",
            "a[gpt-4.1]" to "the key in the bracket at 2 needs quotes",
            "a[\"b" to "the quote at 3 is not closed",
            "a[\"b\"" to "the bracket at 2 is not closed",
            "a[\"b\"]c" to "'c' at 7 follows a key",
            "a]" to "']' at 2 follows a key",
            "a[\"\"]" to "the key in the bracket at 2 is empty",
            "a[\"\\n\"]" to "escape a character",
        )
        for ((path, why) in cases) {
            val error = assertFailsWith<IllegalArgumentException>(path) { ConfigPath.parse(path) }
            val message = error.message.orEmpty()
            assertTrue(message.startsWith("Malformed config path '$path': ") && why in message, message)
        }
    }

    @Test
    fun `a path is written with brackets only for keys that need them, and reads back`() {
        val keys = listOf("providers", "openai-responses", "models", "gpt-6.1-sol", "contextWindow")
        val odd = listOf("a", "x[0]", "say \"hi\"", "back\\slash.")

        assertEquals("providers.openai-responses.models[\"gpt-6.1-sol\"].contextWindow", ConfigPath.format(keys))
        assertEquals("[\"a.b\"].c", ConfigPath.format(listOf("a.b", "c")))
        assertEquals("agent.floor", ConfigPath.format(listOf("agent", "floor")))
        assertEquals("a.b[\"c.d\"]", ConfigPath.join("a.b", "c.d"))
        assertEquals("a", ConfigPath.join("", "a"))
        for (path in listOf(keys, odd)) assertEquals(path, ConfigPath.parse(ConfigPath.format(path)))
    }
}
