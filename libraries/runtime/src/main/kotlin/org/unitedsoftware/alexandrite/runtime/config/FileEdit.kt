package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.serialization.json.JsonElement
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal

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
        change(ConfigChange(keys("change", path), null))
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
        val message = "Cannot $action the config at '${keys.joinToString(".")}': ${e.message}"
        throw refused(ConfigEditRefusal.INVALID_PATH, message, e)
    }

    private fun keys(action: String, path: String): List<String> {
        val keys = path.split('.')
        if (keys.any(String::isEmpty)) {
            throw refused(
                ConfigEditRefusal.INVALID_PATH,
                "Cannot $action the config at '$path': a path is keys joined by dots, none of them empty.",
            )
        }
        return keys
    }
}
