package org.unitedsoftware.alexandrite.provider.openairesponses

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.everyKey
import org.unitedsoftware.alexandrite.provider.common.everyKeyJson
import org.unitedsoftware.alexandrite.provider.common.keys
import org.unitedsoftware.alexandrite.provider.common.responses.ReasoningSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAiResponsesConfigTest {
    private fun read(json: String) =
        Json.decodeFromString(OpenAiResponsesConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint that sets every key reads each as before`() {
        val shared = everyKeyJson("https://proxy.example.com/v1", TurnContextSetting.BAKE)
        val own = """"promptCacheKey": false, "reasoningSummary": null, "nativeSearch": true"""

        val endpoint = read("""{"endpoints": {"x": {$shared, $own}}}""")

        assertEquals(everyKey("https://proxy.example.com/v1", TurnContextSetting.BAKE), endpoint.settings.keys())
        val options = endpoint.options
        assertEquals(
            listOf(false, null, true),
            listOf(options.promptCacheKey, options.reasoningSummary, options.nativeSearch),
        )
    }

    @Test
    fun `an endpoint that sets only what it must takes the defaults of OpenAI`() {
        val endpoint = read("""{"endpoints": {"x": {}}}""")

        val settings = endpoint.settings
        assertEquals("https://api.openai.com/v1", settings.baseUrl)
        assertEquals(TurnContextSetting.TRANSIENT, settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
        val options = endpoint.options
        assertEquals(
            listOf(true, ReasoningSummary.AUTO, false),
            listOf(options.promptCacheKey, options.reasoningSummary, options.nativeSearch),
        )
    }
}
