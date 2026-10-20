package org.unitedsoftware.alexandrite.sdk.tool

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ToolDefinitionTest {
    private fun definition(name: String) = ToolDefinition(name, "Runs the sample", JsonObject(emptyMap()))

    @Test
    fun `an undeclared risk is EXEC`() {
        assertEquals(ToolRisk.EXEC, definition("sample.run").risk)
    }

    @Test
    fun `a tool declares no timeout of its own unless it bounds its calls, by a positive one`() {
        val parameters = JsonObject(emptyMap())

        assertNull(definition("sample.run").timeout)
        assertEquals(10.minutes, ToolDefinition("sample.run", "Runs", parameters, timeout = 10.minutes).timeout)
        for (timeout in listOf(Duration.ZERO, (-1).seconds)) {
            val error = assertFailsWith<IllegalArgumentException> {
                ToolDefinition("sample.run", "Runs", parameters, timeout = timeout)
            }
            assertEquals("Tool 'sample.run' declares a timeout that is not positive.", error.message)
        }
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

    @Test
    fun `a command preview names its program, and a rule subject a well-formed tool and what people see`() {
        val cwd = Path.of("/srv")

        assertFailsWith<IllegalArgumentException> { ReviewPreview.Command(emptyList(), cwd, null, opaque = false) }
        assertFailsWith<IllegalArgumentException> { RuleSubject("command-exec", listOf("ls"), "ls") }
        assertFailsWith<IllegalArgumentException> { RuleSubject("command.exec", listOf("ls"), " ") }
        assertEquals(listOf("./gradlew", "test"), RuleSubject("command.exec", listOf("./gradlew", "test"), "x").tokens)
    }

    @Test
    fun `the risks rise in severity, the ones always gated sharing the highest`() {
        val severities = ToolRisk.entries.associateWith { it.severity }

        assertEquals(listOf(0, 1, 2, 3, 4, 4, 4), severities.values.toList())
        assertEquals(ToolRisk.entries, ToolRisk.entries.sortedBy { it.severity })
    }
}
