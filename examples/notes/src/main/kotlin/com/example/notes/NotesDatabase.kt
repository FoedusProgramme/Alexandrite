package com.example.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import java.sql.Connection
import java.sql.DriverManager

@Singleton
internal class NotesDatabase(private val files: PluginFiles, private val config: NotesConfig) : Lifecycle {
    private val lock = Mutex()

    @Volatile
    private var connection: Connection? = null

    /** Whether [use] still runs blocks. */
    private var serving = true

    override suspend fun onStart() {
        lock.withLock { withContext(Dispatchers.IO) { connection = open() } }
    }

    override suspend fun onDrain() {
        lock.withLock { serving = false }
    }

    override fun onStop() {
        connection?.close()
        connection = null
    }

    /** Runs [block] with the connection, one block at a time. */
    suspend fun <T> use(block: (Connection) -> T): T = lock.withLock {
        val open = connection?.takeIf { serving } ?: throw IllegalStateException("The notes database is closed.")
        withContext(Dispatchers.IO) { block(open) }
    }

    suspend fun clear() {
        use { connection -> connection.createStatement().use { it.executeUpdate("DELETE FROM notes") } }
    }

    private fun open(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:${files.dataDir.resolve(config.fileName).toUri()}")
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS notes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at TEXT NOT NULL)",
                )
            }
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        return connection
    }
}
