package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.testkit.FakeModelServer
import org.foedusprogramme.alexandrite.testkit.FakeResponse
import org.foedusprogramme.alexandrite.testkit.fakeResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDiscoveryTest {
    private val server = FakeModelServer()

    @AfterTest
    fun close() {
        server.close()
    }

    private fun models(profile: Profile, models: Map<String, ModelConfig> = emptyMap()): Map<String, ModelInfo> =
        runBlocking { endpoint(server, profile, models).use { it.models() } }.associateBy { it.id }

    @Test
    fun `vLLM reports the context window of each model`() {
        server.enqueue(fakeResponse { body(listing(Profile.VLLM).toString()) }, "/v1/models")

        val models = models(Profile.VLLM)

        assertEquals(setOf("chat-model", "plain-model"), models.keys)
        assertEquals(32768, models.getValue("chat-model").contextWindow)
        assertFalse(models.getValue("chat-model").parallelToolCalls)
        assertEquals("Bearer $API_KEY", server.requests.single().header("authorization"))
    }

    @Test
    fun `LM Studio reports the context of the loaded instance, vision and its efforts`() {
        server.enqueue(fakeResponse { body(listing(Profile.LMSTUDIO).toString()) }, "/api/v1/models")

        val models = models(Profile.LMSTUDIO)

        assertEquals(setOf("chat-model", "plain-model"), models.keys)
        val chat = models.getValue("chat-model")
        assertEquals(32768, chat.contextWindow)
        assertEquals("Studio chat-model", chat.displayName)
        assertEquals(setOf(MediaKind.IMAGE), chat.inputMedia)
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), chat.reasoningEfforts)
        assertEquals(null, models.getValue("plain-model").contextWindow)
        assertEquals(emptySet(), models.getValue("plain-model").inputMedia)
    }

    @Test
    fun `an older LM Studio falls back to the OpenAI listing`() {
        server.enqueue(FakeResponse.builder(404).body("{}").build(), "/api/v1/models")
        server.enqueue(fakeResponse { body("""{"data":[{"id":"old-model","object":"model"}]}""") }, "/v1/models")

        assertEquals(setOf("old-model"), models(Profile.LMSTUDIO).keys)
    }

    @Test
    fun `DeepSeek reports limits, image input and the efforts it takes`() {
        server.enqueue(fakeResponse { body(listing(Profile.DEEPSEEK).toString()) }, "/v1/models")

        val chat = models(Profile.DEEPSEEK).getValue("chat-model")

        assertEquals(1048576 to 393216, chat.contextWindow to chat.maxOutputTokens)
        assertEquals(setOf(MediaKind.IMAGE), chat.inputMedia)
        assertEquals(
            setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            chat.reasoningEfforts,
        )
        assertEquals("deepseek", chat.dialect.value)
    }

    @Test
    fun `OpenRouter pages through its listing and reads each model's parameters`() {
        val first = buildJsonObject {
            putJsonArray("data") { add(listing(Profile.OPENROUTER)["data"]!!.jsonArray[0]) }
            putJsonObject("links") { put("next", "/v1/models?offset=1") }
        }
        val second = buildJsonObject {
            putJsonArray("data") { add(listing(Profile.OPENROUTER)["data"]!!.jsonArray[1]) }
        }
        server.enqueue(fakeResponse { body(first.toString()) }, "/v1/models")
        server.enqueue(fakeResponse { body(second.toString()) }, "/v1/models")

        val models = models(Profile.OPENROUTER)

        val chat = models.getValue("chat-model")
        assertTrue(chat.nativeTools && chat.parallelToolCalls)
        assertEquals(200000 to 64000, chat.contextWindow to chat.maxOutputTokens)
        assertEquals(ReasoningEffort.entries.toSet(), chat.reasoningEfforts)
        val plain = models.getValue("plain-model")
        assertFalse(plain.nativeTools)
        assertEquals(emptySet(), plain.reasoningEfforts)
        assertEquals("/v1/models?offset=1", server.requests.last().path)
    }

    @Test
    fun `the operator's facts win, except a context window the backend reports`() {
        server.enqueue(fakeResponse { body(listing(Profile.VLLM).toString()) }, "/v1/models")
        val configured = mapOf(
            "chat-model" to ModelConfig(contextWindow = 8192, parallelToolCalls = true, inputMedia = setOf("image")),
            "extra-model" to ModelConfig(contextWindow = 4096, nativeTools = false, displayName = "Extra"),
        )

        val models = models(Profile.VLLM, configured)

        val chat = models.getValue("chat-model")
        assertEquals(32768, chat.contextWindow)
        assertTrue(chat.parallelToolCalls)
        assertEquals(setOf(MediaKind.IMAGE), chat.inputMedia)
        val extra = models.getValue("extra-model")
        assertEquals(4096 to "Extra", extra.contextWindow to extra.displayName)
        assertFalse(extra.nativeTools || extra.parallelToolCalls)
    }

    @Test
    fun `a listing that fails falls back to the configured models, or fails when there are none`() {
        server.enqueue(FakeResponse.builder(401).body("""{"error":{"message":"bad key"}}""").build(), "/v1/models")
        server.enqueue(FakeResponse.builder(401).body("""{"error":{"message":"bad key"}}""").build(), "/v1/models")

        val configured = models(Profile.GENERIC, mapOf("mine" to ModelConfig(contextWindow = 1000)))
        val error = assertFailsWith<ModelException> { models(Profile.GENERIC) }.error

        assertEquals(setOf("mine"), configured.keys)
        assertEquals(ModelErrorKind.AUTHENTICATION, error.kind)
        assertFalse(API_KEY in error.message)
    }

    @Test
    fun `an endpoint that does not discover asks nothing`() {
        val endpoint = endpoint(server, Profile.GENERIC, mapOf("only" to ModelConfig()), discover = false)

        val models = runBlocking { endpoint.use { it.models() } }

        assertEquals(listOf("only"), models.map { it.id })
        assertTrue(server.requests.isEmpty())
    }
}
