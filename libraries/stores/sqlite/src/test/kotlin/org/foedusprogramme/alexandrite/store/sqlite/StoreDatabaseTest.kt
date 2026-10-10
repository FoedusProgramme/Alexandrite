package org.foedusprogramme.alexandrite.store.sqlite

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreDatabaseTest {
    @TempDir
    lateinit var directory: Path

    private val file: Path get() = directory.resolve(StoreDatabase.FILE_NAME)

    private val runs = mutableListOf<String>()

    private val steps = listOf(
        Migration("a") {
            runs += "a"
            execute("CREATE TABLE a (x)")
        },
        Migration("b") {
            runs += "b"
            execute("CREATE TABLE b (x)")
        },
    )

    private fun database(): StoreDatabase = StoreDatabase(TestFiles(directory), MutableClock())

    // Migrations.

    @Test
    fun `a new store gets the newest schema`() {
        withStore(directory) {}

        connect(file).use {
            assertEquals(STORE_MIGRATIONS.size, it.userVersion())
            assertEquals(
                setOf(
                    "chats",
                    "conversations",
                    "current_conversations",
                    "turns",
                    "entries",
                    "media",
                    "entry_media",
                    "chat_states",
                ),
                it.tables(),
            )
        }
    }

    @Test
    fun `a store of version 1 gets the index of the turns that never ended`() {
        storeWithTurn(version = 1)

        val unended = withStore(directory) { conversations.unendedTurns().map { it.id } }

        assertEquals(listOf(TurnId("t1")), unended)
        connect(file).use {
            assertEquals(STORE_MIGRATIONS.size, it.userVersion())
            assertContains(it.indexes("turns"), "turns_unended")
        }
    }

    @Test
    fun `a store of version 2 records each turn's chat as the chat of its key`() {
        storeWithTurn(version = 2)

        val record = withStore(directory) { conversations.turn(TurnId("t1")) }

        assertEquals(main, record?.key)
        assertEquals(chat, record?.chat)
        connect(file).use { assertEquals(STORE_MIGRATIONS.size, it.userVersion()) }
    }

    @Test
    fun `the turns that never ended are found through their index`() {
        val plan = withStore(directory) { rows("EXPLAIN QUERY PLAN $UNENDED_TURNS").map { it.last() as String } }

        assertTrue(plan.any { "USING INDEX turns_unended" in it }, "$plan")
        assertTrue(plan.none { "TEMP B-TREE" in it }, "$plan")
    }

    @Test
    fun `opening a store again runs no step`() {
        openStore(file, steps, MutableClock().instant).close()
        val schema = connect(file).use { it.schema() }

        openStore(file, steps, MutableClock().instant).close()

        assertEquals(listOf("a", "b"), runs)
        assertEquals(schema, connect(file).use { it.schema() })
        assertEquals(2, connect(file).use { it.userVersion() })
    }

    @Test
    fun `a store goes on from the version it has`() {
        openStore(file, steps.take(1), MutableClock().instant).close()
        openStore(file, steps, MutableClock().instant).close()

        assertEquals(listOf("a", "b"), runs)
        assertEquals(setOf("a", "b"), connect(file).use { it.tables() })
    }

    @Test
    fun `a step that fails leaves the store at the version before it`() {
        val failing = steps.take(1) + Migration("c") {
            execute("CREATE TABLE c (x)")
            execute("INSERT INTO missing VALUES (1)")
        }

        val error = assertFailsWith<IllegalStateException> { openStore(file, failing, MutableClock().instant) }

        assertContains(error.message!!, "to schema version 2 (c)")
        connect(file).use {
            assertEquals(1, it.userVersion())
            assertEquals(setOf("a"), it.tables())
        }
    }

    @Test
    fun `a store of a newer version is refused and left unchanged`() {
        connect(file).use {
            it.runSql("CREATE TABLE future (x)")
            it.runSql("PRAGMA user_version = ${STORE_MIGRATIONS.size + 1}")
        }
        val before = Files.readAllBytes(file)

        val error = assertFailsWith<IllegalStateException> { runBlocking { database().onStart() } }

        assertContains(error.message!!, "has schema version ${STORE_MIGRATIONS.size + 1}, which is newer than version")
        assertContentEquals(before, Files.readAllBytes(file))
        assertFalse(Files.exists(directory.resolve("${StoreDatabase.FILE_NAME}-wal")))
    }

    @Test
    fun `a file that is no database is refused and left unchanged`() {
        Files.write(file, ByteArray(4096) { it.toByte() })

        val error = assertFailsWith<IllegalStateException> { runBlocking { database().onStart() } }

        assertContains(error.message!!, "Cannot open the store $file")
        assertContentEquals(ByteArray(4096) { it.toByte() }, Files.readAllBytes(file))
    }

    // The connection.

    @Test
    fun `the connection writes ahead, syncs normally and enforces foreign keys`() {
        val pragmas = withStore(directory) {
            listOf("journal_mode", "synchronous", "foreign_keys", "busy_timeout").map { rows("PRAGMA $it").single() }
        }

        assertEquals(listOf(listOf("wal"), listOf(1), listOf(1), listOf(5000)), pragmas)
    }

    @Test
    fun `a transaction that throws leaves nothing behind`() {
        withStore(directory) {
            assertFailsWith<IllegalStateException> {
                database.transaction {
                    execute("INSERT INTO chats (address, created_at) VALUES ('x', 0)")
                    error("stop")
                }
            }

            assertEquals(emptyList(), rows("SELECT * FROM chats"))
        }
    }

    @Test
    fun `actions after a commit run only once it committed`() {
        val ran = mutableListOf<String>()

        withStore(directory) {
            database.transaction { afterCommit { ran += "committed" } }
            runCatching {
                database.transaction {
                    afterCommit { ran += "rolled back" }
                    error("stop")
                }
            }
        }

        assertEquals(listOf("committed"), ran)
    }

    @Test
    fun `a store refuses blocks before it opens and after it is destroyed`() {
        val database = database()

        val before = assertFailsWith<IllegalStateException> { runBlocking { database.transaction {} } }
        runBlocking { database.onStart() }
        database.onDestroy()
        val after = assertFailsWith<IllegalStateException> { runBlocking { database.transaction {} } }

        assertEquals("The store is not open.", before.message)
        assertEquals("The store is closed.", after.message)
    }

    @Test
    fun `destroying the store waits for the block in flight`() {
        val database = database()
        runBlocking { database.onStart() }
        val entered = CountDownLatch(1)
        var committed = false

        val worker = thread {
            runBlocking {
                database.transaction {
                    entered.countDown()
                    Thread.sleep(200)
                    execute("INSERT INTO chats (address, created_at) VALUES ('x', 0)")
                }
                committed = true
            }
        }
        entered.await()
        database.onDestroy()
        worker.join()

        assertTrue(committed)
        assertEquals(setOf("x"), withStore(directory) { rows("SELECT address FROM chats").map { it.single() }.toSet() })
    }

    /** A store of schema [version] that holds the unended turn `t1` of [main]. */
    private fun storeWithTurn(version: Int) {
        val now = MutableClock().instant
        openStore(file, STORE_MIGRATIONS.take(version), now).use { connection ->
            connection.inTransaction(now) {
                val chatId = chatId(chat)
                execute(
                    "INSERT INTO conversations (id, kind, agent, chat_id, state, created_at, depth) " +
                        "VALUES ('c1', 'user_lane', 'main', ?, 'active', 0, 0)",
                    chatId,
                )
                execute(
                    "INSERT INTO turns (id, conversation, agent, chat_id, kind, depth, started_at) " +
                        "VALUES ('t1', 'c1', 'main', ?, 'message', 0, 0)",
                    chatId,
                )
            }
        }
    }

    private fun java.sql.Connection.indexes(table: String): List<String> = createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'").use {
            buildList { while (it.next()) add(it.getString(1)) }
        }
    }

    private fun java.sql.Connection.schema(): List<String> = createStatement().use { statement ->
        statement.executeQuery("SELECT sql FROM sqlite_master ORDER BY name").use {
            buildList { while (it.next()) add(it.getString(1).orEmpty()) }
        }
    }
}
