package org.unitedsoftware.alexandrite.provider.lmstudio

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import kotlin.test.Test
import kotlin.test.assertEquals

class LmStudioConfigTest {
    private fun read(json: String) = Json.decodeFromString(LmStudioConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint that sets only what it must takes the defaults of LM Studio`() {
        val endpoint = read("""{"endpoints": {"x": {}}}""")

        val settings = endpoint.settings
        assertEquals("http://localhost:1234/v1", settings.baseUrl)
        assertEquals(TurnContextSetting.TRANSIENT, settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
    }
}
