package org.unitedsoftware.alexandrite.sdk.tool

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
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
    fun `a review leaves what its builder is not given unset and rebuilds to an equal one`() {
        val empty = CallReview.builder().build()
        val review = CallReview.builder().risk(ToolRisk.READ_ONLY).exempt(true).person("a person decides").build()

        assertEquals(
            List(6) { null } + listOf(false, false, false, null, null),
            with(empty) {
                listOf(risk, refusal, preview, snapshot, rule, problem)
            } + with(empty) { listOf(exempt, background, untrusted, person, contained) },
        )
        assertEquals(review, review.rebuild { })
        assertEquals(
            CallReview.builder().risk(ToolRisk.READ_ONLY).person("a person decides").build(),
            review.rebuild { exempt(false) },
        )
    }

    @Test
    fun `a tool declares its activity and the arguments that title a call, otherwise OTHER and none`() {
        assertEquals(
            ToolActivity.OTHER to emptyList(),
            definition("sample.run").let {
                it.activity to it.titleArguments
            },
        )

        val read = ToolDefinition(
            "sample.read",
            "Reads",
            JsonObject(emptyMap()),
            activity = ToolActivity.READ,
            titleArguments = listOf("path"),
        )
        assertEquals(ToolActivity.READ to listOf("path"), read.activity to read.titleArguments)
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
    fun `an outgoing file holds no fewer than 0 bytes and has no caption until given`() {
        val chat = ChatAddress(ChannelInstanceId(ChannelType("telegram"), "work"), "1001")

        assertNull(ReviewPreview.OutgoingFile(Path.of("/srv/a.txt"), 0, "e3b0", chat).caption)
        assertFailsWith<IllegalArgumentException> { ReviewPreview.OutgoingFile(Path.of("/srv/a"), -1, "e3b0", chat) }
    }

    @Test
    fun `the risks rise in severity, the ones always gated sharing the highest`() {
        val severities = ToolRisk.entries.associateWith { it.severity }

        assertEquals(listOf(0, 1, 2, 3, 4, 4, 4), severities.values.toList())
        assertEquals(ToolRisk.entries, ToolRisk.entries.sortedBy { it.severity })
    }

    @Test
    fun `an activity keeps the id a version does not know`() {
        assertEquals(12, ToolActivity.entries.size)
        assertEquals("fork", ToolActivity.of("fork").id)
        assertEquals(ToolActivity.READ, ToolActivity.of("read"))
    }
}
