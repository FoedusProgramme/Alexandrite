package org.unitedsoftware.alexandrite.provider.openrouter

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.everyKey
import org.unitedsoftware.alexandrite.provider.common.everyKeyJson
import org.unitedsoftware.alexandrite.provider.common.keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRouterConfigTest {
    private fun read(json: String) =
        Json.decodeFromString(OpenRouterConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint that sets every key reads each as before`() {
        val shared = everyKeyJson("https://proxy.example.com/v1", TurnContextSetting.BAKE)
        val own = """"promptCacheKey": true"""

        val endpoint = read("""{"endpoints": {"x": {$shared, $own}}}""")

        assertEquals(everyKey("https://proxy.example.com/v1", TurnContextSetting.BAKE), endpoint.settings.keys())
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
