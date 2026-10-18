package org.unitedsoftware.alexandrite.runtime.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import java.util.concurrent.ConcurrentHashMap

/** What the stored values of [plugin] share: strict names, lenient JSON and one report per unreadable value. */
internal class PluginValues(private val plugin: String) {
    private val reported = ConcurrentHashMap.newKeySet<Pair<String, Any>>()

    /** Throws when [name] of a [kind], such as `chat state`, is malformed. */
    fun requireName(kind: String, name: String, example: String) {
        require(NAME.matches(name)) {
            "Malformed $kind name '$name' of plugin '$plugin': it must be lowercase words of letters, digits and " +
                "'_', joined by dots, such as \"$example\"."
        }
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): String = JSON.encodeToString(serializer, value)

    /** [json] decoded, null when it cannot be read, which [report] is told once per [name] and [key]. */
    fun <T> decode(serializer: KSerializer<T>, json: String, name: String, key: Any, report: (Exception) -> Unit): T? =
        try {
            JSON.decodeFromString(serializer, json)
        } catch (e: Exception) {
            if (reported.add(name to key)) report(e)
            null
        }
}

/** Logs that [value] of [plugin] cannot be read, for code that runs no runtime. */
internal fun logUnreadable(plugin: String, value: String, error: Exception) {
    LoggerFactory.getLogger(AlexandriteRuntime::class.java)
        .warn("{} of plugin {} cannot be read and counts as absent", value, plugin, error)
}

private val NAME = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")

private val JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
