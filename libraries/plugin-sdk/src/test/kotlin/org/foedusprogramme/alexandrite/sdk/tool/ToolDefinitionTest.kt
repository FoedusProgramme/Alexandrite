package org.foedusprogramme.alexandrite.sdk.tool

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolDefinitionTest {
    @Test
    fun `an undeclared risk is EXEC`() {
        assertEquals(ToolRisk.EXEC, ToolDefinition("sample.run", "Runs the sample", JsonObject(emptyMap())).risk)
    }
}
