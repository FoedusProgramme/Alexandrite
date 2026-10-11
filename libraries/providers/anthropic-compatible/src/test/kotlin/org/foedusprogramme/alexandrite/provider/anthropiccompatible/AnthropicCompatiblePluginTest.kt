package org.foedusprogramme.alexandrite.provider.anthropiccompatible

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.provider.common.messages.sse
import org.foedusprogramme.alexandrite.provider.common.messages.textEvents
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

class AnthropicCompatiblePluginTest {
    private fun harness(config: String) =
        PluginHarness.builder(AlexandriteProviderAnthropicCompatibleIndex()).config(config)

    /** The config of one endpoint `x` with [fields]. */
    private fun endpoint(fields: String = ""): String = """{"endpoints": {"x": {"apiKey": "hunter2"$fields}}}"""

    @Test
    fun `each configured endpoint is contributed under its id with its turn context modes`() = runBlocking {
        val config = """
            {"endpoints": {
                "anthropic": {"apiKey": "sk-plugin-test-key"},
                "newest": {"apiKey": "sk-plugin-test-key", "turnScopedSystem": true,
                    "betas": ["some-beta-2026-01-01"], "promptCaching": false},
                "proxy": {"baseUrl": "https://proxy.example.com/anthropic", "turnContext": "bake",
                    "headers": {"X-Team": "core"}}
            }}
        """.trimIndent()

        harness(config).build().run {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
            assertEquals(listOf("anthropic", "newest", "proxy"), endpoints.map { it.id.value })
            val modes = endpoints.map { endpoint ->
                Trust.entries.map { endpoint.turnContextMode("m", ModelOptions.DEFAULT, it) }
            }
            assertEquals(
                listOf(
                    listOf(TurnContextMode.TRANSIENT, TurnContextMode.TRANSIENT),
                    listOf(TurnContextMode.KEPT_UNRENDERED, TurnContextMode.TRANSIENT),
                    listOf(TurnContextMode.NOT_SUPPORTED, TurnContextMode.NOT_SUPPORTED),
                ),
                modes,
            )
            assertFalse("sk-plugin-test-key" in endpoints.toString())
        }
        assertEquals("https://api.anthropic.com", EndpointConfig().settings().baseUrl)
    }

    @Test
    fun `an endpoint sends its configured headers beside its key`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(sse(*textEvents(listOf("Hi."))))
            val config = """
                {"endpoints": {"proxy": {"baseUrl": "${server.baseUrl}", "apiKey": "sk-plugin-test-key",
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
                ).build()
                endpoint.stream(request).toList()
            }

            val sent = server.requests.single()
            assertEquals("core" to "sk-plugin-test-key", sent.header("x-team") to sent.header("x-api-key"))
            assertEquals(null, sent.header("authorization"))
        }
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            endpoint().replace("\"x\"", "\"Bad_Id\"") to "no endpoint id",
            endpoint(""", "baseUrl": "https://user:hunter2@api.example.com"""") to "baseUrl must be",
            endpoint(""", "turnContext": "sometimes"""") to "turnContext",
            endpoint(""", "headers": {"X-Api-Key": "k"}""") to "may not set x-api-key when apiKey is set",
            endpoint(""", "headers": {"anthropic-beta": "b"}""") to "may not set anthropic-beta",
            endpoint(""", "betas": ["a, b"]""") to "betas holds",
            endpoint(""", "promptCacheKey": true""") to "promptCacheKey",
            endpoint(""", "models": {"m": {"inputMedia": ["smell"]}}""") to "inputMedia holds",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.anthropic-compatible" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
