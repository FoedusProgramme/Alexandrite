package org.unitedsoftware.alexandrite.provider.deepseek

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.everyKey
import org.unitedsoftware.alexandrite.provider.common.everyKeyJson
import org.unitedsoftware.alexandrite.provider.common.keys
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepSeekConfigTest {
    private fun read(json: String) = Json.decodeFromString(DeepSeekConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint that sets every key reads each as before`() {
        val shared = everyKeyJson("https://proxy.example.com/v1", TurnContextSetting.BAKE)

        val endpoint = read("""{"endpoints": {"x": {$shared}}}""")

        assertEquals(everyKey("https://proxy.example.com/v1", TurnContextSetting.BAKE), endpoint.settings.keys())
    }

    @Test
    fun `an endpoint that sets only what it must takes the defaults of DeepSeek`() {
        val endpoint = read("""{"endpoints": {"x": {"apiKey": "k"}}}""")

        val settings = endpoint.settings
        assertEquals("https://api.deepseek.com" to TurnContextSetting.BAKE, settings.baseUrl to settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
    }
}
