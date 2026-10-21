package org.unitedsoftware.alexandrite.provider.deepseek

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepSeekConfigTest {
    private fun read(json: String) = Json.decodeFromString(DeepSeekConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint that sets only what it must takes the defaults of DeepSeek`() {
        val endpoint = read("""{"endpoints": {"x": {"apiKey": "k"}}}""")

        val settings = endpoint.settings
        assertEquals("https://api.deepseek.com" to TurnContextSetting.BAKE, settings.baseUrl to settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
    }
}
