package org.foedusprogramme.alexandrite.store.sqlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The store's SQLite database, `store.db` in the plugin's data directory, which runs one block at a time. */
@Singleton
internal class StoreDatabase(private val files: PluginFiles, private val clock: Clock) : Lifecycle {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1, "alexandrite-store-sqlite")

    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var closed = false

    val file: Path get() = files.dataDir.resolve(FILE_NAME)

    override suspend fun onStart() {
        withContext(dispatcher) {
            check(connection == null && !closed) { "The store was opened before." }
            connection = openStore(file, STORE_MIGRATIONS, now())
        }
    }

    /** Closes the database once the components that use it have stopped and the plugin scopes are cancelled. */
    override fun onDestroy() {
        runBlocking(dispatcher) {
            closed = true
            connection?.close()
            connection = null
        }
    }

    /** Runs [block] in a transaction of its own, after the blocks given before it. */
    suspend fun <T> transaction(block: Tx.() -> T): T = withContext(dispatcher) {
        val open = connection
            ?: throw IllegalStateException(if (closed) "The store is closed." else "The store is not open.")
        open.inTransaction(now(), block)
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    companion object {
        const val FILE_NAME: String = "store.db"
    }
}
