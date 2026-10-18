package org.foedusprogramme.alexandrite.mcp

import org.foedusprogramme.alexandrite.sdk.tool.ToolNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpNamesTest {
    @Test
    fun `a name is lowercased, other characters made underscores, and prefixed without a letter first`() {
        assertEquals("get_item", McpNames.sanitize("Get-Item"))
        assertEquals("h_llo", McpNames.sanitize("héllo"))
        assertEquals("read_file_v2", McpNames.sanitize("read.file v2"))
        assertEquals("t_1password", McpNames.sanitize("1password"))
        assertEquals("t__private", McpNames.sanitize("_private"))
        assertEquals("t_", McpNames.sanitize(""))
    }

    @Test
    fun `each name lies under the server's and is a tool name`() {
        val names = McpNames.assign("github", listOf("create_issue", "Get-Item", "1st"))

        assertEquals(
            mapOf(
                "create_issue" to "mcp.github.create_issue",
                "1st" to "mcp.github.t_1st",
                "Get-Item" to "mcp.github.get_item",
            ),
            names,
        )
        assertTrue(names.values.all { ToolNames.PATTERN.matches(it) })
    }

    @Test
    fun `on a collision the name that needs no change keeps it, and the other gets a stable hash`() {
        val names = McpNames.assign("files", listOf("A-B", "a_b"))
        val again = McpNames.assign("files", listOf("a_b", "A-B"))

        assertEquals("mcp.files.a_b", names["a_b"])
        assertTrue(names.getValue("A-B").matches(Regex("mcp\\.files\\.a_b_[0-9a-f]{6}")), names.toString())
        assertEquals(names, again)
    }

    @Test
    fun `a name too long is cut to 64 characters with a hash, which tells apart names that share their start`() {
        val long = "a".repeat(80)
        val names = McpNames.assign("server", listOf(long + "x", long + "y"))

        for (name in names.values) {
            assertEquals(64, name.length)
            assertTrue(ToolNames.PATTERN.matches(name), name)
        }
        assertNotEquals(names[long + "x"], names[long + "y"])
        assertEquals(names, McpNames.assign("server", listOf(long + "y", long + "x")))
    }
}
