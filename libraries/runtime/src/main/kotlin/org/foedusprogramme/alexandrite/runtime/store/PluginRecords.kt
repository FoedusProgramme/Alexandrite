package org.foedusprogramme.alexandrite.runtime.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.store.RecordCollection
import org.foedusprogramme.alexandrite.sdk.store.RecordStore
import org.foedusprogramme.alexandrite.sdk.store.Records
import org.foedusprogramme.alexandrite.sdk.store.StoredRecord
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Told about the first stored value of each record that cannot be read. */
internal fun interface UnreadableRecordListener {
    fun onUnreadable(plugin: String, collection: String, key: String, error: Exception)
}

/** The [Records] of [plugin], stored in [store]. */
internal class PluginRecords(
    private val plugin: String,
    private val store: RecordStore,
    private val listener: UnreadableRecordListener,
) : Records {
    private val collections = ConcurrentHashMap<String, Collection<*>>()
    private val reported = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    override fun <T : Any> collection(name: String, serializer: KSerializer<T>): RecordCollection<T> {
        require(NAME.matches(name)) {
            "Malformed record collection name '$name' of plugin '$plugin': it must be lowercase words of letters, " +
                "digits and '_', joined by dots, such as \"trusted_folders\"."
        }
        val collection = collections.computeIfAbsent(name) { Collection(name, serializer) }
        require(collection.serializer.descriptor == serializer.descriptor) {
            "Record collection '$name' of plugin '$plugin' exists already with another type."
        }
        @Suppress("UNCHECKED_CAST")
        return collection as RecordCollection<T>
    }

    private inner class Collection<T : Any>(private val name: String, val serializer: KSerializer<T>) :
        RecordCollection<T> {
        override suspend fun get(key: String): StoredRecord<T>? = store.get(plugin, name, checked(key))?.let(::decoded)

        override suspend fun put(key: String, value: T, by: UserAddress?): StoredRecord<T> =
            store.put(plugin, name, checked(key), JSON.encodeToString(serializer, value), by).holding(value)

        override suspend fun remove(key: String): Boolean = store.remove(plugin, name, checked(key))

        override suspend fun list(): List<StoredRecord<T>> = store.list(plugin, name).mapNotNull(::decoded)

        private fun decoded(record: StoredRecord<String>): StoredRecord<T>? {
            val value = try {
                JSON.decodeFromString(serializer, record.value)
            } catch (e: Exception) {
                if (reported.add(name to record.key)) listener.onUnreadable(plugin, name, record.key, e)
                return null
            }
            return record.holding(value)
        }

        private fun checked(key: String): String {
            require(key.isNotEmpty()) { "A record of collection '$name' of plugin '$plugin' needs a key." }
            return key
        }
    }
}

/** The [Records] of [plugin] over [store] for code that runs no runtime, which logs values it cannot read. */
@InternalAlexandriteApi
public fun standaloneRecords(plugin: String, store: RecordStore): Records =
    PluginRecords(plugin, store) { owner, collection, key, error ->
        LoggerFactory.getLogger(AlexandriteRuntime::class.java).warn(
            "record '{}' of collection '{}' of plugin {} cannot be read and counts as absent",
            key,
            collection,
            owner,
            error,
        )
    }

private fun <T : Any> StoredRecord<*>.holding(value: T): StoredRecord<T> =
    StoredRecord(key, value, createdAt, createdBy, updatedAt, updatedBy)

private val NAME = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")

private val JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
