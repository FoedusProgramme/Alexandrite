package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
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

class OpenAiCompatiblePluginTest {
    private fun harness(config: String) =
        PluginHarness.builder(AlexandriteProviderOpenaiCompatibleIndex()).config(config)

    /** The config of one endpoint `x` at an example URL, with [fields] added. */
    private fun endpoint(fields: String = ""): String =
        """{"endpoints": {"x": {"baseUrl": "https://api.example.com"$fields}}}"""

    @Test
    fun `each configured endpoint is contributed under its id with its turn context mode`() = runBlocking {
        val config = """
            {"endpoints": {
                "vllm": {"baseUrl": "http://127.0.0.1:8000/v1", "apiKey": "sk-plugin-test-key"},
                "proxy": {"baseUrl": "https://proxy.example.com/v1", "turnContext": "bake",
                    "headers": {"X-Team": "core"}, "promptCacheKey": true}
            }}
        """.trimIndent()

        harness(config).build().run {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
            assertEquals(listOf("vllm", "proxy"), endpoints.map { it.id.value })
            val modes = endpoints.map { it.turnContextMode("m", ModelOptions.DEFAULT, Trust.UNTRUSTED) }
            assertEquals(listOf(TurnContextMode.TRANSIENT, TurnContextMode.NOT_SUPPORTED), modes)
            assertFalse("sk-plugin-test-key" in endpoints.toString())
        }
        Unit
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            endpoint().replace("\"x\"", "\"Bad_Id\"") to "no endpoint id",
            endpoint().replace("https://", "https://user:hunter2@") to "baseUrl must be",
            endpoint(""", "profile": "deepseek"""") to "profile",
            endpoint(""", "turnContext": "sometimes"""") to "turnContext",
            endpoint(""", "headers": {"Host": "h"}""") to "may not set host",
            endpoint(""", "models": {"m": {"inputMedia": ["smell"]}}""") to "inputMedia holds",
            """{"endpoints": {"x": {"apiKey": "k"}}}""" to "baseUrl",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.openai-compatible" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
