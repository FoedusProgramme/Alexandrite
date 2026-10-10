package org.foedusprogramme.alexandrite.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.testHardFloor
import org.foedusprogramme.alexandrite.testkit.testToolContext
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolsPluginTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `the plugin contributes the read-only fs tools, which check paths against the bound floor`() {
        val root = directory.toRealPath()
        val secret = Files.createDirectories(root.resolve("secret")).resolve("key.txt")
        Files.writeString(secret, "SECRET")
        lateinit var risks: Map<String, ToolRisk>
        lateinit var refused: String

        runBlocking {
            PluginHarness.builder(AlexandriteToolsIndex()).hardFloor(testHardFloor(listOf(secret.parent))).build().run {
                val tools = getAll<Tool>()
                risks = tools.associate { it.definition.name to it.definition.risk }
                val read = tools.single { it.definition.name == "fs.read" }
                val result = read.execute(JsonObject(mapOf("path" to JsonPrimitive("$secret"))), testToolContext())
                refused = (result.content.single() as TextPart).text
            }
        }

        assertEquals(
            mapOf("fs.glob" to ToolRisk.READ_ONLY, "fs.grep" to ToolRisk.READ_ONLY, "fs.read" to ToolRisk.READ_ONLY),
            risks,
        )
        assertEquals("$secret is protected (a place the test protects): no file tool may read it.", refused)
    }
}
