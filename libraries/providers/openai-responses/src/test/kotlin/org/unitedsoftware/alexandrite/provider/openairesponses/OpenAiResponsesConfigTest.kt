package org.unitedsoftware.alexandrite.provider.openairesponses

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.responses.ReasoningSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAiResponsesConfigTest {
    private fun read(json: String) =
        Json.decodeFromString(OpenAiResponsesConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint reads the provider's own options`() {
        val own = """"promptCacheKey": false, "reasoningSummary": null, "nativeSearch": true"""

        val endpoint = read("""{"endpoints": {"x": {$own}}}""")

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
