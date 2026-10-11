package org.foedusprogramme.alexandrite.store.sqlite

import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.RecordStore
import org.foedusprogramme.alexandrite.sdk.store.StoredRecord
import java.sql.ResultSet

@Singleton
@Binds(RecordStore::class)
internal class SqliteRecordStore(private val database: StoreDatabase) : RecordStore {
    override suspend fun get(plugin: String, collection: String, key: String): StoredRecord<String>? =
        database.transaction {
            queryOne(
                "SELECT $COLUMNS FROM records WHERE plugin = ? AND collection = ? AND record_key = ?",
                plugin,
                collection,
                key,
            ) { record() }
        }

    override suspend fun put(
        plugin: String,
        collection: String,
        key: String,
        json: String,
        by: UserAddress?,
    ): StoredRecord<String> = database.transaction {
        queryOne(
            "INSERT INTO records (plugin, collection, record_key, value, created_at, created_by, updated_at, " +
                "updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (plugin, collection, record_key) DO UPDATE " +
                "SET value = excluded.value, updated_at = excluded.updated_at, updated_by = excluded.updated_by " +
                "RETURNING $COLUMNS",
            plugin,
            collection,
            key,
            json,
            now,
            by?.toString(),
            now,
            by?.toString(),
        ) { record() } ?: throw IllegalStateException("The store kept no record $key of $plugin's $collection.")
    }

    override suspend fun remove(plugin: String, collection: String, key: String): Boolean = database.transaction {
        execute(
            "DELETE FROM records WHERE plugin = ? AND collection = ? AND record_key = ?",
            plugin,
            collection,
            key,
        ) > 0
    }

    override suspend fun list(plugin: String, collection: String): List<StoredRecord<String>> = database.transaction {
        query(
            "SELECT $COLUMNS FROM records WHERE plugin = ? AND collection = ? ORDER BY rowid",
            plugin,
            collection,
        ) { record() }
    }
}

private const val COLUMNS = "record_key, value, created_at, created_by, updated_at, updated_by"

private fun ResultSet.record(): StoredRecord<String> = StoredRecord(
    getString("record_key"),
    getString("value"),
    instant("created_at"),
    getString("created_by")?.let(UserAddress::parseOrNull),
    instant("updated_at"),
    getString("updated_by")?.let(UserAddress::parseOrNull),
)
