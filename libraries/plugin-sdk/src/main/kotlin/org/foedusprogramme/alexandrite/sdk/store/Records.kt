package org.foedusprogramme.alexandrite.sdk.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal

/** The plugin's own collections of keyed records, which survive restarts. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface Records {
    /**
     * The collection [name] of this plugin, whose values [serializer] reads and writes. A name is lowercase words of
     * letters, digits and `_`, joined by dots.
     *
     * A type may drop fields and gain fields with defaults.
     */
    public fun <T : Any> collection(name: String, serializer: KSerializer<T>): RecordCollection<T>
}

public inline fun <reified T : Any> Records.collection(name: String): RecordCollection<T> =
    collection(name, serializer())

/** Records by non-empty keys, where a stored value that cannot be read counts as absent. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface RecordCollection<T : Any> {
    /** The record [key], null when there is none. */
    public suspend fun get(key: String): StoredRecord<T>?

    /**
     * Stores [value] as the record [key] for [by], null for the host, keeping when and by whom it was created, and
     * returns the record.
     */
    public suspend fun put(key: String, value: T, by: UserAddress?): StoredRecord<T>

    /** Removes the record [key], false when there is none. */
    public suspend fun remove(key: String): Boolean

    /** The records, in the order they were created. */
    public suspend fun list(): List<StoredRecord<T>>
}
