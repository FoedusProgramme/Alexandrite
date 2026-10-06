package com.example.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

private val SUFFIXES = listOf("", "-wal", "-shm")

@Singleton
internal class NotesDatabase(private val files: PluginFiles, private val config: NotesConfig) : Lifecycle {
    private val lock = Mutex()

    @Volatile
    private var connection: Connection? = null

    override suspend fun onStart() {
        lock.withLock { withContext(Dispatchers.IO) { connection = open() } }
    }

    override fun onStop() {
        connection?.close()
        connection = null
    }

    /** Runs [block] with the connection, one block at a time. */
    suspend fun <T> use(block: (Connection) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            block(connection ?: throw IllegalStateException("The notes database is closed."))
        }
    }

    /** Deletes the database file and starts an empty one. */
    suspend fun delete() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                connection?.close()
                connection = null
                val file = file()
                for (suffix in SUFFIXES) Files.deleteIfExists(file.resolveSibling("${file.fileName}$suffix"))
                connection = open()
            }
        }
    }

    private fun file(): Path = files.dataDir.resolve(config.fileName)

    private fun open(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:${file()}")
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
