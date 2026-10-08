package com.example.notes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.testToolContext
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class NotesPluginTest {
    @TempDir
    lateinit var directory: Path

    private fun harness(
        config: String? = null,
        dataRoot: Path = directory,
        block: suspend PluginHarness.Running.() -> Unit,
    ): Termination {
        val builder = PluginHarness.builder(NotesIndex()).dataRoot(dataRoot).zone(ZoneId.of("Asia/Shanghai"))
        config?.let(builder::config)
        return runBlocking { builder.build().run(block) }
    }

    private suspend fun PluginHarness.Running.call(tool: String, vararg arguments: Pair<String, String>): ToolResult {
        val json = JsonObject(arguments.associate { (name, value) -> name to JsonPrimitive(value) })
        return getAll<Tool>().single { it.definition.name == tool }.execute(json, testToolContext())
    }

    private val ToolResult.text: String get() = content.joinToString("") { (it as TextPart).text }

    @Test
    fun `notes are added, listed and reset`() {
        harness {
            assertEquals("No notes.", call("notes.list").text)
            assertEquals("Saved note #1.", call("notes.add", "text" to "buy milk").text)
            assertEquals("Saved note #2.", call("notes.add", "text" to " call home ").text)

            val listed = call("notes.list").text.lines()

            assertEquals(2, listed.size)
            assertTrue(NOTE.matches(listed[0]), listed[0])
            assertTrue(listed[1].startsWith("#2 (") && listed[1].endsWith(") call home"), listed[1])
            assertEquals("Deleted every note.", call("notes.reset").text)
            assertEquals("No notes.", call("notes.list").text)
            assertEquals("Saved note #3.", call("notes.add", "text" to "again").text)
        }
    }

    @Test
    fun `a note without text is refused`() {
        harness {
            val result = call("notes.add", "text" to "  ")

            assertTrue(result.isError)
            assertEquals("No notes.", call("notes.list").text)
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
        harness { listed = call("notes.list").text }

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
    fun `a file name that is no plain file name is refused at start`() {
        val names = listOf(
            "../notes.db",
            "a/notes.db",
            "a\\\\notes.db",
            "C:notes.db",
            "notes.db?mode=memory",
            "x#y.db",
            "..",
            " ",
        )
        for (name in names) {
            val error = assertFailsWith<RuntimeStartException>(name) {
                harness("""{"fileName": "$name"}""") { fail("the block ran") }
            }

            assertContains(
                error.message!!,
                "Invalid config at 'plugins.notes': fileName must be a plain file name, such as notes.db",
            )
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a data directory whose name holds URL characters is used as it is`() {
        val root = directory.resolve("odd #% dir?journal_mode=MEMORY&x=1")

        harness(dataRoot = root) {
            call("notes.add", "text" to "kept")

            assertTrue(Files.isRegularFile(dataDir.resolve("notes.db")))
        }
        harness(dataRoot = root) { assertContains(call("notes.list").text, "kept") }
    }

    @Test
    fun `a stop waits for the block in flight, closes the connection and refuses later blocks`() {
        lateinit var database: NotesDatabase
        val entered = CountDownLatch(1)
        var closedInBlock: Boolean? = null
        lateinit var worker: Thread

        harness {
            database = get<NotesDatabase>()
            worker = thread {
                runBlocking {
                    database.use { connection ->
                        entered.countDown()
                        Thread.sleep(200)
                        closedInBlock = connection.isClosed
                    }
                }
            }
            entered.await()
        }
        worker.join()

        assertEquals(false, closedInBlock)
        val error = assertFailsWith<IllegalStateException> { runBlocking { database.use { } } }
        assertEquals("The notes database is closed.", error.message)
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
