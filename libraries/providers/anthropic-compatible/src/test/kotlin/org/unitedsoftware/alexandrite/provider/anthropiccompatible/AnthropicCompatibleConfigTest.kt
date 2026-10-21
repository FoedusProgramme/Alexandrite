package org.unitedsoftware.alexandrite.provider.anthropiccompatible

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicCompatibleConfigTest {
    private fun read(json: String) =
        Json.decodeFromString(AnthropicCompatibleConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint reads the provider's own options`() {
        val own = """
            "promptCaching": false, "betas": ["some-beta-2026-01-01"], "turnScopedSystem": true, "nativeSearch": true
        """.trimIndent()

        val endpoint = read("""{"endpoints": {"x": {$own}}}""")

        val options = endpoint.options
        assertEquals(listOf("some-beta-2026-01-01"), options.betas)
        assertEquals(
            listOf(false, true, true),
            listOf(options.promptCaching, options.turnScopedSystem, options.nativeSearch),
        )
    }

    @Test
    fun `an endpoint that sets only what it must takes the defaults of Anthropic`() {
        val endpoint = read("""{"endpoints": {"x": {}}}""")

        val settings = endpoint.settings
        assertEquals("https://api.anthropic.com", settings.baseUrl)
        assertEquals(TurnContextSetting.TRANSIENT, settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
        val options = endpoint.options
        assertEquals(emptyList(), options.betas)
        assertEquals(
            listOf(true, false, false),
            listOf(options.promptCaching, options.turnScopedSystem, options.nativeSearch),
        )
        assertEquals(MessagesEndpoint.AUTH_HEADER, endpoint.settings.authHeader)
        assertEquals(MessagesEndpoint.PROVIDER_HEADERS, endpoint.settings.providerHeaders)
    }
}
