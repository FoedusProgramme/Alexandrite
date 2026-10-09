package org.foedusprogramme.alexandrite.provider.common

import org.foedusprogramme.alexandrite.sdk.config.Secret
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndpointSettingsTest {
    private fun refused(expected: String, block: () -> Unit) {
        val message = assertFailsWith<IllegalArgumentException> { block() }.message.orEmpty()
        assertTrue(expected in message, message)
    }

    @Test
    fun `a base URL is plain http or https`() {
        for (url in listOf("ftp://example.com", "https://user:pw@example.com", "https://example.com?a=b", "nope")) {
            refused("baseUrl must be") { EndpointSettings(url) }
        }
        EndpointSettings("http://127.0.0.1:1234/v1")
    }

    @Test
    fun `headers are well-formed and leave the provider's own headers alone`() {
        refused("malformed header name") {
            EndpointSettings("https://a.example", headers = mapOf("a b" to Secret("x")))
        }
        refused("may not set host") { EndpointSettings("https://a.example", headers = mapOf("Host" to Secret("x"))) }
        refused("authorization when apiKey") {
            EndpointSettings("https://a.example", Secret("k"), headers = mapOf("Authorization" to Secret("x")))
        }
        EndpointSettings("https://a.example", headers = mapOf("Authorization" to Secret("x")))
    }

    @Test
    fun `configured models and their facts are well-formed`() {
        refused("models holds") { EndpointSettings("https://a.example", models = mapOf("" to ModelConfig())) }
        refused("inputMedia holds 'smell'") { ModelConfig(inputMedia = setOf("smell")) }
        refused("reasoningEfforts holds 'huge'") { ModelConfig(reasoningEfforts = setOf("huge")) }
        refused("contextWindow must be positive") { ModelConfig(contextWindow = 0) }
        refused("every timeout must be positive") { TimeoutConfig(idleSeconds = 0) }
    }

    @Test
    fun `endpoint ids follow the id grammar`() {
        refused("endpoints holds 'Bad_Id'") { requireEndpointIds(listOf("deepseek", "Bad_Id")) }
        requireEndpointIds(listOf("deepseek", "local-studio2"))
    }
}
