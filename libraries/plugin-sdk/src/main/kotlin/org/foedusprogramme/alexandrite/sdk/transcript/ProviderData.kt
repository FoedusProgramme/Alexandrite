package org.foedusprogramme.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Data that providers keep for themselves, one JSON object per wire dialect. */
@Poko
public class ProviderData internal constructor(public val entries: Map<Dialect, JsonObject>) {
    /** The data of [dialect], null when there is none or it does not decode. */
    public fun <T> decode(dialect: Dialect, deserializer: DeserializationStrategy<T>): T? {
        val data = entries[dialect] ?: return null
        return try {
            LENIENT.decodeFromJsonElement(deserializer, data)
        } catch (e: SerializationException) {
            null
        }
    }

    /** This data with [value] as the data of [dialect]. */
    public fun with(dialect: Dialect, value: JsonObject): ProviderData = ProviderData(entries + (dialect to value))

    public companion object {
        public val EMPTY: ProviderData = ProviderData(emptyMap())

        private val LENIENT = Json { ignoreUnknownKeys = true }
    }
}
