package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SecretTest {
    @Serializable
    data class BotConfig(val token: Secret, val name: String = "bot")

    @Test
    fun `a decoded secret reveals its value and is masked when printed`() {
        val root = Json.parseToJsonElement("""{"channels": {"bot": {"token": "s3cr3t"}}}""").jsonObject

        val config = JsonConfigSource(root).section("channels.bot", BotConfig.serializer())

        assertEquals("s3cr3t", config.token.reveal())
        assertEquals("BotConfig(token=Secret(***), name=bot)", config.toString())
        assertEquals("Secret(***)", "${config.token}")
    }

    @Test
    fun `a secret round-trips as a plain string`() {
        val config = BotConfig(Secret("s3cr3t"))

        val encoded = Json.encodeToString(BotConfig.serializer(), config)

        assertEquals("""{"token":"s3cr3t"}""", encoded)
        assertEquals(config, Json.decodeFromString(BotConfig.serializer(), encoded))
    }

    @Test
    fun `secrets are equal by value`() {
        assertEquals(Secret("a"), Secret("a"))
        assertNotEquals(Secret("a"), Secret("b"))
    }
}
