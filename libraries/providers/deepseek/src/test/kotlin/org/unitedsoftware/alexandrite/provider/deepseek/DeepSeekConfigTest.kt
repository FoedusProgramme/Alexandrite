package org.unitedsoftware.alexandrite.provider.deepseek

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeepSeekConfigTest {
    private fun read(json: String) = Json.decodeFromString(DeepSeekConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint that sets only what it must takes the defaults of DeepSeek`() {
        val endpoint = read("""{"endpoints": {"x": {"apiKey": "k"}}}""")

        val settings = endpoint.settings
        assertEquals("https://api.deepseek.com" to TurnContextSetting.BAKE, settings.baseUrl to settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
    }

    @Test
    fun `an endpoint id that breaks the id grammar is refused while the config is read`() {
        val error = assertFailsWith<IllegalArgumentException> {
            read("""{"endpoints": {"Bad_Id": {"apiKey": "k"}}}""")
        }

        assertContains(error.message.orEmpty(), "Malformed endpoint id 'Bad_Id'")
    }
}
