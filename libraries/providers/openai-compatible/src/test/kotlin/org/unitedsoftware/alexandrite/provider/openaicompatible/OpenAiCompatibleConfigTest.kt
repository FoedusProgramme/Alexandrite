package org.unitedsoftware.alexandrite.provider.openaicompatible

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiCompatibleConfigTest {
    private fun read(json: String) =
        Json.decodeFromString(OpenAiCompatibleConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint reads the provider's own options`() {
        val own = """"promptCacheKey": true"""

        val endpoint = read("""{"endpoints": {"x": {"baseUrl": "https://api.example.com/v1", $own}}}""")

        assertTrue(endpoint.options.promptCacheKey)
    }

    @Test
    fun `an endpoint that sets only what it must takes the defaults of an OpenAI-compatible backend`() {
        val endpoint = read("""{"endpoints": {"x": {"baseUrl": "https://api.example.com/v1"}}}""")

        val settings = endpoint.settings
        assertEquals(TurnContextSetting.TRANSIENT, settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
        assertFalse(endpoint.options.promptCacheKey)
    }
}
