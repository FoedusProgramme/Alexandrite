package org.foedusprogramme.alexandrite.sdk.tool

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolDefinitionTest {
    private fun definition(name: String) = ToolDefinition(name, "Runs the sample", JsonObject(emptyMap()))

    @Test
    fun `an undeclared risk is EXEC`() {
        assertEquals(ToolRisk.EXEC, definition("sample.run").risk)
    }

    @Test
    fun `a tool name is lowercase words joined by dots, 64 characters at most`() {
        val valid = listOf("echo", "notes.add", "a_b.c2.d_", "a" + ".b".repeat(31) + "c")
        val invalid =
            listOf("", "Notes.add", "notes-add", "notes..add", ".notes", "notes.", "1notes", "notes.2x", "a".repeat(65))

        for (name in valid) assertTrue(ToolNames.PATTERN.matches(name), name)
        for (name in invalid) assertFalse(ToolNames.PATTERN.matches(name), name)
        val error = assertFailsWith<IllegalArgumentException> { definition("notes-add") }
        assertContains(error.message!!, "Malformed tool name 'notes-add'")
    }

    @Test
    fun `a tool name maps to its wire form and back`() {
        val names = listOf("echo", "notes.add", "notes_add", "a.b_c.d")

        assertEquals(listOf("echo", "notes-add", "notes_add", "a-b_c-d"), names.map(ToolNames::wire))
        assertEquals(names, names.map { ToolNames.fromWire(ToolNames.wire(it)) })
        for (wire in listOf("notes.add", "Notes-add", "notes--add", "-notes", "notes-", "")) {
            assertNull(ToolNames.fromWire(wire), wire)
        }
        assertFailsWith<IllegalArgumentException> { ToolNames.wire("notes-add") }
    }
}
