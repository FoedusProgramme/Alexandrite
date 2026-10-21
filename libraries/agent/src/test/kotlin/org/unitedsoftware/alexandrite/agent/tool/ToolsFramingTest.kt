package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ToolsFramingTest {
    private val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name")) { "No golden file $name." }.readText().removeSuffix("\n")

    private fun tool(
        name: String,
        description: String = "A tool.",
        parameters: JsonObject = schema,
        risk: ToolRisk = ToolRisk.READ_ONLY,
        timeout: Duration? = null,
    ) = ToolDefinition(name, description, parameters, risk, timeout)

    private fun text(entry: UserEntry?): String? = (entry?.parts?.single() as? ContextPart)?.text

    @Test
    fun `a changed description, an added and a removed tool make the notice of the golden file`() {
        val before = fingerprint(listOf(tool("notes.write"), tool("permission.status", "Shows the mode.")))
        val after = fingerprint(
            listOf(
                tool("fs.edit"),
                tool("permission.status", "Shows the mode and the approvals."),
                tool("command.exec", risk = ToolRisk.EXEC),
            ),
        )

        assertEquals(
            UserEntry(
                null,
                listOf(ContextPart("alexandrite.tools", golden("tools.v1.txt"))),
                UserOrigin.SystemWritten("alexandrite.tools"),
            ),
            toolsEntry(before, after),
        )
    }

    @Test
    fun `the same tools make no notice, whatever the order of the tools and of their schemas' keys`() {
        val type = "type" to JsonPrimitive("object")
        val path = JsonObject(mapOf("path" to JsonObject(mapOf("type" to JsonPrimitive("string")))))
        val properties = "properties" to path
        val read = tool("fs.read", parameters = JsonObject(mapOf(type, properties)))
        val reordered = tool("fs.read", parameters = JsonObject(mapOf(properties, type)))
        val before = fingerprint(listOf(read, tool("fs.list")))

        assertNull(toolsEntry(before, fingerprint(listOf(tool("fs.list"), reordered))))
        assertTrue(before.values.all { Regex("[0-9a-f]{64}").matches(it) })
    }

    @Test
    fun `a changed risk, timeout or schema counts as a change, and the changed tools share one line`() {
        val before = fingerprint(listOf(tool("fs.list"), tool("fs.read"), tool("web.fetch")))
        val after = fingerprint(
            listOf(
                tool("fs.list", parameters = JsonObject(mapOf("type" to JsonPrimitive("array")))),
                tool("fs.read", timeout = 30.seconds),
                tool("web.fetch", risk = ToolRisk.NETWORK_READ),
            ),
        )

        val lines = text(toolsEntry(before, after))!!.lines()
        val change = "Changed: fs.list, fs.read, web.fetch (their descriptions, parameters, risks or timeouts)."

        assertEquals(listOf(change), lines.filter { it.matches(Regex("(Added|Removed|Changed): .*")) })
    }

    @Test
    fun `tools that are all gone are named as removed`() {
        val notice = text(toolsEntry(fingerprint(listOf(tool("permission.status"), tool("fs.read"))), emptyMap()))

        assertTrue("\nRemoved: fs.read, permission.status.\n" in notice!!, notice)
    }
}
