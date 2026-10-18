package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.serialization.json.JsonElement
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditor
import org.unitedsoftware.alexandrite.sdk.config.ConfigLiteral
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds

internal class PluginConfigEditor(private val plugin: EnabledPlugin, private val writer: ConfigWriter) : ConfigEditor {
    override suspend fun edit(block: ConfigEdit.() -> Unit) {
        writer.edit(plugin, block)
    }
}

/** One edit of the plugin whose config root is [root], which opens the file at its first call. */
internal class PluginEdit(private val root: String, private val open: () -> OpenFile) : ConfigEdit {
    private val rootKeys = root.split('.')

    val changes = mutableListOf<ConfigChange>()

    /** Null until the edit opened the file. */
    var file: OpenFile? = null
        private set

    /** The file's text with the changes so far. */
    lateinit var text: ConfigText
        private set

    override fun get(path: String): JsonElement? {
        val keys = rootKeys + keys("read", path)
        return at("read", keys) { opened().get(keys) }
    }

    override fun getLiteral(path: String): ConfigLiteral? = get(path)?.let(ConfigFile::literal)

    override fun set(path: String, value: JsonElement) {
        change(ConfigChange(changeable(path), value))
    }

    override fun setLiteral(path: String, value: ConfigLiteral) {
        set(path, ConfigFile.written(value))
    }

    override fun remove(path: String) {
        change(ConfigChange(changeable(path), null))
    }

    private fun change(change: ConfigChange) {
        val keys = rootKeys + change.path
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
                "Cannot $action the config at '$root.$path': a path is keys joined by dots, none of them empty.",
            )
        }
        return keys
    }

    private fun changeable(path: String): List<String> {
        val keys = keys("change", path)
        if (keys.first() == PluginIds.ENABLED_KEY) {
            throw refused(
                ConfigEditRefusal.INVALID_PATH,
                "Cannot change the config at '$root.$path': only the operator switches a plugin on and off.",
            )
        }
        return keys
    }
}
