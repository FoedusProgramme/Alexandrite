package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonConfigSourceTest {
    @Serializable
    data class ExecConfig(val command: String, val timeoutSeconds: Int = 30)

    @Serializable
    data class WebConfig(val enabled: Boolean = true, val userAgent: String = "alexandrite")

    @Serializable
    data class BotConfig(val token: Secret)

    private fun source(json: String) = JsonConfigSource(Json.parseToJsonElement(json).jsonObject)

    private fun ConfigSource.failure(path: String, deserializer: DeserializationStrategy<*>) =
        assertFailsWith<ConfigException> { section(path, deserializer) }

    @Test
    fun `a nested section decodes its subtree`() {
        val source = source("""{"tools": {"exec": {"command": "make", "timeoutSeconds": 5}}}""")

        assertEquals(ExecConfig("make", 5), source.section("tools.exec", ExecConfig.serializer()))
    }

    @Test
    fun `a missing section decodes with its defaults`() {
        val source = source("""{"tools": {"exec": {"command": "make"}}}""")

        assertEquals(WebConfig(), source.section("tools.web", WebConfig.serializer()))
        assertEquals(WebConfig(), source.section("channels.web", WebConfig.serializer()))
    }

    @Test
    fun `a missing required field fails naming the path`() {
        val withoutField = source("""{"tools": {"exec": {"timeoutSeconds": 5}}}""")
        val withoutSection = source("{}")

        for (source in listOf(withoutField, withoutSection)) {
            val error = source.failure("tools.exec", ExecConfig.serializer())
            assertEquals("tools.exec", error.path)
            assertTrue(error.message!!.startsWith("Invalid config at 'tools.exec': Field 'command' is required"))
        }
    }

    @Test
    fun `a non-object at the path fails`() {
        val source = source("""{"tools": {"exec": "make", "web": null}, "channels": []}""")

        assertEquals(
            "Invalid config at 'tools.exec': 'tools.exec' is not an object",
            source.failure("tools.exec", ExecConfig.serializer()).message,
        )
        assertEquals(
            "Invalid config at 'tools.web': 'tools.web' is not an object",
            source.failure("tools.web", WebConfig.serializer()).message,
        )
        assertEquals(
            "Invalid config at 'channels.web': 'channels' is not an object",
            source.failure("channels.web", WebConfig.serializer()).message,
        )
    }

    @Test
    fun `a decoding error does not quote the config`() {
        val error = source("""{"bot": {"token": "s3cr3t", "tokn": "s3cr3t"}}""").failure("bot", BotConfig.serializer())

        assertTrue("unknown key 'tokn'" in error.message!!)
        assertTrue(generateSequence<Throwable>(error) { it.cause }.none { "s3cr3t" in it.message.orEmpty() })
    }

    @Test
    fun `a malformed path is rejected`() {
        assertFailsWith<IllegalArgumentException> { source("{}").section("tools..exec", WebConfig.serializer()) }
        assertFailsWith<IllegalArgumentException> { source("{}").section("", WebConfig.serializer()) }
    }
}
