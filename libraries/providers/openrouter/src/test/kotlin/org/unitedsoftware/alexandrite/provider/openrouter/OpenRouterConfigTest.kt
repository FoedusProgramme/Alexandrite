package org.unitedsoftware.alexandrite.provider.openrouter

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRouterConfigTest {
    private fun read(json: String) =
        Json.decodeFromString(OpenRouterConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint reads the provider's own options`() {
        val own = """"promptCacheKey": true"""

        val endpoint = read("""{"endpoints": {"x": {"apiKey": "k", $own}}}""")

        assertTrue(endpoint.options.promptCacheKey)
    }

    @Test
    fun `an endpoint that sets only what it must takes the defaults of OpenRouter`() {
        val endpoint = read("""{"endpoints": {"x": {"apiKey": "k"}}}""")

        val settings = endpoint.settings
        assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
        assertEquals(TurnContextSetting.TRANSIENT, settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
        assertFalse(endpoint.options.promptCacheKey)
    }
}
