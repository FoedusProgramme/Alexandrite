package org.unitedsoftware.alexandrite.provider.lmstudio

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.everyKey
import org.unitedsoftware.alexandrite.provider.common.everyKeyJson
import org.unitedsoftware.alexandrite.provider.common.keys
import kotlin.test.Test
import kotlin.test.assertEquals

class LmStudioConfigTest {
    private fun read(json: String) = Json.decodeFromString(LmStudioConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint that sets every key reads each as before`() {
        val shared = everyKeyJson("https://proxy.example.com/v1", TurnContextSetting.BAKE)

        val endpoint = read("""{"endpoints": {"x": {$shared}}}""")

        assertEquals(everyKey("https://proxy.example.com/v1", TurnContextSetting.BAKE), endpoint.settings.keys())
    }

    @Test
    fun `an endpoint that sets only what it must takes the defaults of LM Studio`() {
        val endpoint = read("""{"endpoints": {"x": {}}}""")

        val settings = endpoint.settings
        assertEquals("http://localhost:1234/v1", settings.baseUrl)
        assertEquals(TurnContextSetting.TRANSIENT, settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
    }
}
