package org.foedusprogramme.alexandrite.provider.openairesponses

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.foedusprogramme.alexandrite.provider.common.responses.sse
import org.foedusprogramme.alexandrite.provider.common.responses.textEvents
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.testkit.FakeModelServer
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiResponsesPluginTest {
    private fun harness(config: String) =
        PluginHarness.builder(AlexandriteProviderOpenaiResponsesIndex()).config(config)

    /** The config of one endpoint `x` with [fields]. */
    private fun endpoint(fields: String = ""): String = """{"endpoints": {"x": {"apiKey": "hunter2"$fields}}}"""

    @Test
    fun `each configured endpoint is contributed under its id with its turn context mode`() = runBlocking {
        val config = """
            {"endpoints": {
                "openai": {"apiKey": "sk-plugin-test-key"},
                "proxy": {"baseUrl": "https://proxy.example.com/v1", "turnContext": "bake",
                    "headers": {"X-Team": "core"}, "reasoningSummary": null, "promptCacheKey": false}
            }}
        """.trimIndent()

        harness(config).build().run {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
            assertEquals(listOf("openai", "proxy"), endpoints.map { it.id.value })
            val modes = endpoints.map { it.turnContextMode("m", ModelOptions.DEFAULT, Trust.UNTRUSTED) }
            assertEquals(listOf(TurnContextMode.TRANSIENT, TurnContextMode.NOT_SUPPORTED), modes)
            assertFalse("sk-plugin-test-key" in endpoints.toString())
        }
        assertEquals("https://api.openai.com/v1", EndpointConfig().settings().baseUrl)
    }

    @Test
    fun `an endpoint posts to responses with its key, headers and cache key`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(sse(*textEvents(listOf("Hi."))))
            val config = """
                {"endpoints": {"proxy": {"baseUrl": "${server.baseUrl}/v1", "apiKey": "sk-plugin-test-key",
                    "headers": {"X-Team": "core"}, "discover": false, "models": {"m": {}}}}}
            """.trimIndent()

            harness(config).build().run {
                val endpoint = getAll<ModelProvider>().single().endpoints.single()
                val turn = testTurn()
                val request = ModelRequest.builder(
                    ModelRef(endpoint.id, "m"),
                    listOf(testUserEntry(turn)),
                    0,
                    RequestIds(turn.conversation, turn.id, 0),
                ).cacheKey("cache-7").build()
                endpoint.stream(request).toList()
            }

            val sent = server.requests.single()
            assertEquals("/v1/responses", sent.path)
            assertEquals("core" to "Bearer sk-plugin-test-key", sent.header("x-team") to sent.header("authorization"))
            assertEquals("cache-7", sent.json()["prompt_cache_key"]?.jsonPrimitive?.content)
            assertEquals("false", sent.json()["store"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            endpoint().replace("\"x\"", "\"Bad_Id\"") to "no endpoint id",
            endpoint(""", "baseUrl": "https://user:hunter2@api.example.com"""") to "baseUrl must be",
            endpoint(""", "turnContext": "sometimes"""") to "turnContext",
            endpoint(""", "headers": {"Authorization": "k"}""") to "may not set authorization when apiKey is set",
            endpoint(""", "reasoningSummary": "verbose"""") to "reasoningSummary must be",
            endpoint(""", "models": {"m": {"reasoningEfforts": ["lots"]}}""") to "reasoningEfforts holds",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.openai-responses" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
