package com.example.notes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.testToolContext
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotesPluginTest {
    @TempDir
    lateinit var directory: Path

    private fun harness(config: String? = null): PluginHarness {
        val builder = PluginHarness.builder(NotesIndex()).dataRoot(directory).zone(ZoneId.of("Asia/Shanghai"))
        config?.let(builder::config)
        return builder.build().apply { runBlocking { start() } }
    }

    private fun PluginHarness.call(tool: String, vararg arguments: Pair<String, String>): ToolResult {
        val json = JsonObject(arguments.associate { (name, value) -> name to JsonPrimitive(value) })
        return runBlocking { getAll<Tool>().single { it.definition.name == tool }.execute(json, testToolContext()) }
    }

    @Test
    fun `notes are added, listed and reset`() {
        harness().use { harness ->
            assertEquals("No notes.", harness.call("notes.list").content)
            assertEquals("Saved note #1.", harness.call("notes.add", "text" to "buy milk").content)
            assertEquals("Saved note #2.", harness.call("notes.add", "text" to " call home ").content)

            val listed = harness.call("notes.list").content.lines()

            assertEquals(2, listed.size)
            assertTrue(NOTE.matches(listed[0]), listed[0])
            assertTrue(listed[1].startsWith("#2 (") && listed[1].endsWith(") call home"), listed[1])
            assertEquals("Deleted every note.", harness.call("notes.reset").content)
            assertEquals("No notes.", harness.call("notes.list").content)
            assertEquals("Saved note #1.", harness.call("notes.add", "text" to "again").content)
        }
    }

    @Test
    fun `a note without text is refused`() {
        harness().use { harness ->
            val result = harness.call("notes.add", "text" to "  ")

            assertTrue(result.isError)
            assertEquals("No notes.", harness.call("notes.list").content)
        }
    }

    @Test
    fun `the tools declare their risks`() {
        val risks = harness().use { harness ->
            harness.getAll<Tool>().associate { it.definition.name to it.definition.risk }
        }

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
        harness().use { harness ->
            harness.call("notes.add", "text" to "kept")

            assertEquals(directory.resolve("plugins/notes"), harness.dataDir)
            assertTrue(Files.isRegularFile(harness.dataDir.resolve("notes.db")))
        }

        assertContains(harness().use { it.call("notes.list").content }, "kept")
    }

    @Test
    fun `the config section names the database file`() {
        harness("""{"fileName": "journal.db"}""").use { harness ->
            harness.call("notes.add", "text" to "x")

            assertTrue(Files.isRegularFile(harness.dataDir.resolve("journal.db")))
            assertFalse(Files.exists(harness.dataDir.resolve("notes.db")))
        }
    }

    @Test
    fun `a file name with a directory is refused at start`() {
        val error = assertFailsWith<RuntimeStartException> { harness("""{"fileName": "../notes.db"}""") }

        assertContains(
            error.message!!,
            "Invalid config at 'plugins.notes': fileName must name a file without a directory",
        )
    }

    @Test
    fun `a stop closes the connection`() {
        harness().use { harness ->
            val connection = runBlocking { harness.get<NotesDatabase>().use { it } }

            runBlocking { harness.stop() }

            assertTrue(connection.isClosed)
        }
    }

    private companion object {
        val NOTE = Regex("""#1 \(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+08:00\) buy milk""")
    }
}
