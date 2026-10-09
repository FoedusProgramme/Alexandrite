package org.foedusprogramme.alexandrite.provider.common

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFlavor
import org.foedusprogramme.alexandrite.provider.common.chat.FIXTURE_API_KEY
import org.foedusprogramme.alexandrite.provider.common.chat.chatEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
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

    private fun models(
        flavor: ChatFlavor = ChatFlavor.STANDARD,
        models: Map<String, ModelConfig> = emptyMap(),
    ): Map<String, ModelInfo> =
        runBlocking { chatEndpoint(server, flavor, models).use { it.models() } }.associateBy { it.id }

    private fun listing(vararg models: JsonObject): String = buildJsonObject {
        put("object", "list")
        putJsonArray("data") { models.forEach { add(it) } }
    }.toString()

    private fun model(id: String, field: String? = null, window: Int = 0): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "model")
        field?.let { put(it, window) }
    }

    @Test
    fun `the standard listing reads the context window from whichever field the backend fills`() {
        server.enqueue(
            fakeResponse {
                body(
                    listing(
                        model("vllm", "max_model_len", 32768),
                        model("router", "context_length", 200000),
                        model("other", "context_window", 8192),
                        model("bare"),
                    ),
                )
            },
            "/v1/models",
        )

        val models = models()

        assertEquals(
            mapOf("vllm" to 32768, "router" to 200000, "other" to 8192, "bare" to null),
            models.mapValues { it.value.contextWindow },
        )
        val vllm = models.getValue("vllm")
        assertTrue(vllm.nativeTools && vllm.parallelToolCalls && vllm.streaming)
        assertEquals(ChatFlavor.OPENAI_CHAT to emptySet(), vllm.dialect to vllm.reasoningEfforts)
        assertEquals("Bearer $FIXTURE_API_KEY", server.requests.single().header("authorization"))
    }

    @Test
    fun `the operator's facts win, except a context window the backend reports`() {
        server.enqueue(fakeResponse { body(listing(model("chat-model", "max_model_len", 32768))) }, "/v1/models")
        val configured = mapOf(
            "chat-model" to ModelConfig(contextWindow = 8192, parallelToolCalls = false, inputMedia = setOf("image")),
            "extra-model" to ModelConfig(contextWindow = 4096, nativeTools = false, displayName = "Extra"),
        )

        val models = models(models = configured)

        val chat = models.getValue("chat-model")
        assertEquals(32768, chat.contextWindow)
        assertFalse(chat.parallelToolCalls)
        assertEquals(setOf(MediaKind.IMAGE), chat.inputMedia)
        val extra = models.getValue("extra-model")
        assertEquals(4096 to "Extra", extra.contextWindow to extra.displayName)
        assertFalse(extra.nativeTools || extra.parallelToolCalls)
    }

    @Test
    fun `a listing's URLs, pages, facts and defaults decide what the endpoint reports`() {
        val listing = object : ModelListing() {
            override val defaults: ModelFacts = ModelFacts(id = null, nativeTools = true, parallelToolCalls = false)

            override fun urls(baseUrl: String): List<String> = listOf("$baseUrl/catalog", "$baseUrl/models")

            override fun next(page: JsonObject): String? = page.obj("links")?.string("next")

            override fun facts(model: JsonObject): ModelFacts =
                ModelFacts(id = model.string("id"), displayName = model.string("name"))
        }
        val first = buildJsonObject {
            putJsonArray("data") {
                add(
                    buildJsonObject {
                        put("id", "a")
                        put("name", "Model A")
                    },
                )
            }
            putJsonObject("links") { put("next", "/v1/models?page=2") }
        }
        server.enqueue(FakeResponse.builder(404).body("{}").build(), "/v1/catalog")
        server.enqueue(fakeResponse { body(first.toString()) }, "/v1/models")
        server.enqueue(fakeResponse { body(listing(model("b"))) }, "/v1/models")

        val models = models(ChatFlavor(Dialect("custom"), listing))

        assertEquals(listOf("a", "b"), models.keys.toList())
        assertEquals("Model A", models.getValue("a").displayName)
        assertTrue(models.values.all { it.nativeTools && !it.parallelToolCalls && it.dialect == Dialect("custom") })
        assertEquals("/v1/models?page=2", server.requests.last().path)
    }

    @Test
    fun `a listing that fails falls back to the configured models, or fails when there are none`() {
        server.enqueue(FakeResponse.builder(401).body("""{"error":{"message":"bad key"}}""").build(), "/v1/models")
        server.enqueue(FakeResponse.builder(401).body("""{"error":{"message":"bad key"}}""").build(), "/v1/models")

        val configured = models(models = mapOf("mine" to ModelConfig(contextWindow = 1000)))
        val error = assertFailsWith<ModelException> { models() }.error

        assertEquals(setOf("mine"), configured.keys)
        assertEquals(ModelErrorKind.AUTHENTICATION, error.kind)
        assertFalse(FIXTURE_API_KEY in error.message)
    }

    @Test
    fun `a listing in no JSON object breaks the protocol`() {
        server.enqueue(fakeResponse { body("[]") }, "/v1/models")

        assertEquals(ModelErrorKind.PROTOCOL, assertFailsWith<ModelException> { models() }.error.kind)
    }

    @Test
    fun `an endpoint that does not discover asks nothing`() {
        val endpoint = chatEndpoint(server, ChatFlavor.STANDARD, mapOf("only" to ModelConfig()), discover = false)

        val models = runBlocking { endpoint.use { it.models() } }

        assertEquals(listOf("only"), models.map { it.id })
        assertTrue(server.requests.isEmpty())
    }
}
