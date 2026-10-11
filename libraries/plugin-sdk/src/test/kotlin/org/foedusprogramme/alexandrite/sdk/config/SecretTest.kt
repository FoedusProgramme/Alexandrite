package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SecretTest {
    @Serializable
    data class BotConfig(val token: Secret, val name: String = "bot")

    @Test
    fun `a decoded secret reveals its value and is masked when printed`() {
        val tree = Json.parseToJsonElement("""{"token": "s3cr3t"}""").jsonObject

        val config = Json.decodeFromJsonElement(BotConfig.serializer(), tree)

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
        assertEquals(Secret("a").hashCode(), Secret("a").hashCode())
        assertNotEquals(Secret("a"), Secret("b"))
    }

    @Serializable
    data class Nested(val bots: List<BotConfig>, val backup: Secret? = null)

    @Test
    fun `the values of the secrets decoded on this thread are collected`() {
        val tree = Json.parseToJsonElement("""{"bots": [{"token": "one"}, {"token": "two"}], "backup": "three"}""")
        val values = mutableListOf<String>()

        val config = collectingSecrets(values) { Json.decodeFromJsonElement(Nested.serializer(), tree) }
        Json.decodeFromJsonElement(Nested.serializer(), tree)

        assertEquals(listOf("one", "two", "three"), values)
        assertEquals("two", config.bots[1].token.reveal())
    }

    private fun decode(token: String): BotConfig =
        Json.decodeFromString(BotConfig.serializer(), """{"token": "$token"}""")

    @Test
    fun `the outer collector is back after a nested call and after a block that throws`() {
        val outer = mutableListOf<String>()
        val inner = mutableListOf<String>()
        val failed = mutableListOf<String>()

        collectingSecrets(outer) {
            decode("one")
            collectingSecrets(inner) { decode("two") }
            decode("three")
            assertFailsWith<IllegalStateException> {
                collectingSecrets(failed) {
                    decode("four")
                    error("broken")
                }
            }
            decode("five")
        }
        decode("six")

        assertEquals(
            listOf(listOf("one", "three", "five"), listOf("two"), listOf("four")),
            listOf(outer, inner, failed),
        )
    }

    @Test
    fun `secrets decoded on other threads are not collected`() {
        val values = mutableListOf<String>()
        val elsewhere = mutableListOf<String>()
        val collecting = CountDownLatch(1)
        val decoded = CountDownLatch(1)
        val other = Thread {
            collectingSecrets(elsewhere) {
                collecting.countDown()
                decoded.await()
            }
        }

        other.start()
        collecting.await()
        collectingSecrets(values) {
            val thread = Thread { decode("other thread") }
            thread.start()
            thread.join()
            decode("this thread")
        }
        decode("outside")
        decoded.countDown()
        other.join()

        assertEquals(listOf("this thread"), values)
        assertEquals(emptyList(), elsewhere)
    }

    @Test
    fun `a secret is its own type on the JVM`() {
        assertEquals(Secret::class.java, BotConfig::class.java.getMethod("getToken").returnType)
    }
}
