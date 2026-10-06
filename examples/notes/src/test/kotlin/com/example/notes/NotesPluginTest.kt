package com.example.notes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.testToolContext
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class NotesPluginTest {
    @TempDir
    lateinit var directory: Path

    private fun harness(config: String? = null, block: suspend PluginHarness.Running.() -> Unit): Termination {
        val builder = PluginHarness.builder(NotesIndex()).dataRoot(directory).zone(ZoneId.of("Asia/Shanghai"))
        config?.let(builder::config)
        return runBlocking { builder.build().run(block) }
    }

    private suspend fun PluginHarness.Running.call(tool: String, vararg arguments: Pair<String, String>): ToolResult {
        val json = JsonObject(arguments.associate { (name, value) -> name to JsonPrimitive(value) })
        return getAll<Tool>().single { it.definition.name == tool }.execute(json, testToolContext())
    }

    @Test
    fun `notes are added, listed and reset`() {
        harness {
            assertEquals("No notes.", call("notes.list").content)
            assertEquals("Saved note #1.", call("notes.add", "text" to "buy milk").content)
            assertEquals("Saved note #2.", call("notes.add", "text" to " call home ").content)

            val listed = call("notes.list").content.lines()

            assertEquals(2, listed.size)
            assertTrue(NOTE.matches(listed[0]), listed[0])
            assertTrue(listed[1].startsWith("#2 (") && listed[1].endsWith(") call home"), listed[1])
            assertEquals("Deleted every note.", call("notes.reset").content)
            assertEquals("No notes.", call("notes.list").content)
            assertEquals("Saved note #1.", call("notes.add", "text" to "again").content)
        }
    }

    @Test
    fun `a note without text is refused`() {
        harness {
            val result = call("notes.add", "text" to "  ")

            assertTrue(result.isError)
            assertEquals("No notes.", call("notes.list").content)
        }
    }

    @Test
    fun `the tools declare their risks`() {
        lateinit var risks: Map<String, ToolRisk>

        harness { risks = getAll<Tool>().associate { it.definition.name to it.definition.risk } }

        assertEquals(
            mapOf(
                "notes.add" to ToolRisk.AGENT_STATE,
                "notes.list" to ToolRisk.READ_ONLY,
                "notes.reset" to ToolRisk.PERSISTENT_STATE,
            ),
            risks,
        )
    }

    @Test
    fun `the database lives in the plugin's data directory`() {
        lateinit var listed: String

        harness {
            call("notes.add", "text" to "kept")

            assertEquals(directory.resolve("plugins/notes"), dataDir)
            assertTrue(Files.isRegularFile(dataDir.resolve("notes.db")))
        }
        harness { listed = call("notes.list").content }

        assertContains(listed, "kept")
    }

    @Test
    fun `the config section names the database file`() {
        harness("""{"fileName": "journal.db"}""") {
            call("notes.add", "text" to "x")

            assertTrue(Files.isRegularFile(dataDir.resolve("journal.db")))
            assertFalse(Files.exists(dataDir.resolve("notes.db")))
        }
    }

    @Test
    fun `a file name with a directory is refused at start`() {
        val termination = harness("""{"fileName": "../notes.db"}""") { fail("the block ran") }

        assertContains(
            assertIs<Termination.Cause.StartFailed>(termination.cause).error.message!!,
            "Invalid config at 'plugins.notes': fileName must name a file without a directory",
        )
    }

    @Test
    fun `a stop closes the connection`() {
        lateinit var connection: Connection

        harness { connection = get<NotesDatabase>().use { it } }

        assertTrue(connection.isClosed)
    }

    private companion object {
        val NOTE = Regex("""#1 \(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+08:00\) buy milk""")
    }
}
