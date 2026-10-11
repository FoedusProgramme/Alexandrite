package org.foedusprogramme.alexandrite.sdk.store

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.di.BoundSpi
import java.time.Instant

/**
 * The records of every plugin's [Records].
 *
 * - Only a store backend implements it, and only the runtime's [Records] call it.
 * - Every call is atomic, and a call that throws changes nothing.
 * - Times are the store's, to the millisecond.
 */
@BoundSpi
public interface RecordStore {
    /** The record [key] of the [collection] of [plugin], its value as JSON, null when there is none. */
    public suspend fun get(plugin: String, collection: String, key: String): StoredRecord<String>?

    /**
     * Stores [json] as the value of the record [key] for [by], null for the host, and returns the record: a new one,
     * or the one stored before with when and by whom it was created kept.
     */
    public suspend fun put(
        plugin: String,
        collection: String,
        key: String,
        json: String,
        by: UserAddress?,
    ): StoredRecord<String>

    /** Removes the record [key], false when there is none. */
    public suspend fun remove(plugin: String, collection: String, key: String): Boolean

    /** The records of the [collection] of [plugin], in the order they were created. */
    public suspend fun list(plugin: String, collection: String): List<StoredRecord<String>>
}

/** A record of a collection, with when and by whom it was created and last stored. */
@Poko
public class StoredRecord<out T : Any>(
    public val key: String,
    public val value: T,
    public val createdAt: Instant,
    /** Null for the host. */
    public val createdBy: UserAddress?,
    public val updatedAt: Instant,
    /** Null for the host. */
    public val updatedBy: UserAddress?,
)
