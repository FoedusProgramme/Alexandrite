package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolTitlesTest {
    private fun definition(titleArguments: List<String>) = ToolDefinition(
        "test.call",
        "Calls.",
        JsonObject(emptyMap()),
        ToolRisk.READ_ONLY,
        activity = ToolActivity.OTHER,
        titleArguments = titleArguments,
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `no arguments title the call by its name`() {
        assertEquals("test.call", toolTitle(definition(listOf("path")), null))
        assertEquals("test.call", toolTitle(definition(listOf("path")), JsonObject(emptyMap())))
    }

    @Test
    fun `every present title argument joins the title, in order, blank ones skipped`() {
        val definition = definition(listOf("path", "pattern"))

        assertEquals("calculator *", toolTitle(definition, args("path" to "calculator", "pattern" to "*")))
        assertEquals("calculator", toolTitle(definition, args("path" to "calculator", "pattern" to " ")))
        assertEquals("*", toolTitle(definition, args("pattern" to "*")))
    }

    @Test
    fun `an array argument joins its elements with spaces among the other arguments`() {
        val definition = definition(listOf("argv"))
        val argv = JsonObject(mapOf("argv" to JsonArray(listOf("ls", "-la").map(::JsonPrimitive))))

        assertEquals("ls -la", toolTitle(definition, argv))
    }

    @Test
    fun `a token that is the workspace becomes a dot, one inside it loses that prefix`() {
        val workspace = Path.of("/srv/workspaces/main")

        assertEquals(".", relativized("/srv/workspaces/main", workspace))
        assertEquals("calculator", relativized("/srv/workspaces/main/calculator", workspace))
        assertEquals(
            "calculator/pyproject.toml",
            relativized("/srv/workspaces/main/calculator/pyproject.toml", workspace),
        )
        assertEquals(
            "git -C calculator status",
            relativized("git -C /srv/workspaces/main/calculator status", workspace),
        )
    }

    @Test
    fun `tokens outside the workspace, and the title itself with no workspace, are left as they are`() {
        val workspace = Path.of("/srv/workspaces/main")

        assertEquals("/srv/notes.md", relativized("/srv/notes.md", workspace))
        assertEquals("/srv/workspaces/main/calculator", relativized("/srv/workspaces/main/calculator", null))
    }
}
