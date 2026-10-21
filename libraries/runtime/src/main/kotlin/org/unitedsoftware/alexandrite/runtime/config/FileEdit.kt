package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigPath

/** One edit of the whole config file, which opens the file at its first call. */
internal class FileEdit(private val open: () -> OpenFile) : ConfigEdit {
    val changes = mutableListOf<ConfigChange>()

    /** Null until the edit opened the file. */
    var file: OpenFile? = null
        private set

    /** The file's text with the changes so far. */
    lateinit var text: ConfigText
        private set

    override fun get(path: String): JsonElement? {
        val keys = keys("read", path)
        return at("read", keys) { opened().get(keys) }
    }

    override fun set(path: String, value: JsonElement) {
        change(ConfigChange(keys("change", path), value))
    }

    override fun remove(path: String) {
        val keys = keys("change", path)
        if (at("change", keys) { opened().get(keys) } == null) {
            bracketHint(keys, null, ::held)?.let {
                throw refused(ConfigEditRefusal.INVALID_PATH, "Nothing is set at '${ConfigPath.format(keys)}'. $it")
            }
        }
        change(ConfigChange(keys, null))
    }

    private fun held(keys: List<String>): JsonElement? = try {
        if (keys.isEmpty()) Json.parseToJsonElement(text.text.removePrefix(BYTE_ORDER_MARK)) else text.get(keys)
    } catch (e: ConfigPathException) {
        null
    }

    private fun change(change: ConfigChange) {
        val keys = change.path
        text = at("change", keys) { change.value?.let { opened().set(keys, it) } ?: opened().remove(keys) }
        changes += change
    }

    private fun opened(): ConfigText {
        if (file == null) {
            val opened = open()
            text = ConfigText(opened.text)
            file = opened
        }
        return text
    }

    private fun <T> at(action: String, keys: List<String>, block: () -> T): T = try {
        block()
    } catch (e: ConfigPathException) {
        val message = "Cannot $action the config at '${ConfigPath.format(keys)}': ${e.message}"
        throw refused(ConfigEditRefusal.INVALID_PATH, message, e)
    }

    private fun keys(action: String, path: String): List<String> = try {
        ConfigPath.parse(path)
    } catch (e: IllegalArgumentException) {
        throw refused(
            ConfigEditRefusal.INVALID_PATH,
            "Cannot $action the config: ${e.message} $PATH_FORM",
            e,
        )
    }
}

private const val PATH_FORM =
    "A path is keys joined by dots, a key that holds a dot in brackets and quotes, such as " +
        "providers.openai-responses.endpoints.main.models[\"gpt-4.1\"].contextWindow."

/** The hint for [keys] that split a key with dots, null when none looks split. */
internal fun bracketHint(keys: List<String>, unknown: Set<String>?, held: (List<String>) -> JsonElement?): String? {
    for (split in 1 until keys.size) {
        if (unknown != null && keys[split] !in unknown) continue
        val holder = held(keys.take(split - 1)) as? JsonObject
        val joined = (split until keys.size).lastOrNull { end ->
            holder?.containsKey(keys.subList(split - 1, end + 1).joinToString(".")) == true
        } ?: if (unknown != null && keys.getOrNull(split - 2) == MODELS) maxOf(split, keys.size - 2) else continue
        val suggested = keys.take(split - 1) + keys.subList(split - 1, joined + 1).joinToString(".") +
            keys.drop(joined + 1)
        return "Keys with dots go in brackets: ${ConfigPath.format(suggested)}."
    }
    return null
}

/** The keys that [problems] call unknown. */
internal fun unknownKeys(problems: List<String>): Set<String> = problems.filter { "unknown key" in it }
    .flatMapTo(HashSet()) { problem -> QUOTED.findAll(problem).map { it.groupValues[1] } }

private const val MODELS = "models"

private val QUOTED = Regex("'([^']*)'")
