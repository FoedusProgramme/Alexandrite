package org.foedusprogramme.alexandrite.provider.openrouter

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRouterPluginTest {
    private fun harness(config: String) = PluginHarness.builder(AlexandriteProviderOpenrouterIndex()).config(config)

    @Test
    fun `an endpoint needs only its key and keeps turn context transient unless told otherwise`() = runBlocking {
        val config = """
            {"endpoints": {
                "router": {"apiKey": "sk-plugin-test-key", "headers": {"X-Title": "Alexandrite"}},
                "baked": {"apiKey": "sk-plugin-test-key", "turnContext": "bake", "promptCacheKey": true}
            }}
        """.trimIndent()

        harness(config).build().run {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
            assertEquals(listOf("router", "baked"), endpoints.map { it.id.value })
            val modes = endpoints.map { it.turnContextMode("m", ModelOptions.DEFAULT, Trust.UNTRUSTED) }
            assertEquals(listOf(TurnContextMode.TRANSIENT, TurnContextMode.NOT_SUPPORTED), modes)
            assertFalse("sk-plugin-test-key" in endpoints.toString())
        }
        assertEquals("https://openrouter.ai/api/v1", EndpointConfig(Secret("k")).settings().baseUrl)
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            """{"endpoints": {"x": {"baseUrl": "https://api.example.com"}}}""" to "apiKey",
            """{"endpoints": {"x": {"apiKey": "hunter2", "headers": {"Authorization": "b"}}}}""" to "authorization",
            """{"endpoints": {"x": {"apiKey": "hunter2", "profile": "openrouter"}}}""" to "profile",
            """{"endpoints": {"Bad_Id": {"apiKey": "hunter2"}}}""" to "no endpoint id",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.openrouter" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
