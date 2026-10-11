package org.foedusprogramme.alexandrite.runtime.store

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.store.RecordStore
import org.foedusprogramme.alexandrite.sdk.store.StoredRecord
import java.time.Clock
import java.time.temporal.ChronoUnit

/** A [RecordStore] that keeps the records in memory, timed by [clock]. */
@InternalAlexandriteApi
public class MemoryRecordStore(private val clock: Clock = Clock.systemUTC()) : RecordStore {
    private val collections = HashMap<Pair<String, String>, LinkedHashMap<String, StoredRecord<String>>>()

    override suspend fun get(plugin: String, collection: String, key: String): StoredRecord<String>? =
        synchronized(collections) { collections[plugin to collection]?.get(key) }

    override suspend fun put(
        plugin: String,
        collection: String,
        key: String,
        json: String,
        by: UserAddress?,
    ): StoredRecord<String> = synchronized(collections) {
        val records = collections.getOrPut(plugin to collection) { LinkedHashMap() }
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val before = records[key]
        StoredRecord(key, json, before?.createdAt ?: now, if (before == null) by else before.createdBy, now, by)
            .also { records[key] = it }
    }

    override suspend fun remove(plugin: String, collection: String, key: String): Boolean =
        synchronized(collections) { collections[plugin to collection]?.remove(key) != null }

    override suspend fun list(plugin: String, collection: String): List<StoredRecord<String>> =
        synchronized(collections) { collections[plugin to collection]?.values?.toList().orEmpty() }
}
