package org.foedusprogramme.alexandrite.runtime.config

import kotlinx.serialization.json.JsonElement
import org.foedusprogramme.alexandrite.sdk.config.ConfigEdit
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditRefusal
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditor
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds

internal class PluginConfigEditor(private val plugin: EnabledPlugin, private val writer: ConfigWriter) : ConfigEditor {
    override suspend fun edit(block: ConfigEdit.() -> Unit) {
        val changes = Changes(plugin.member.index.configRoot).apply(block).changes
        if (changes.isNotEmpty()) writer.write(plugin, changes)
    }
}

private class Changes(private val root: String) : ConfigEdit {
    val changes = mutableListOf<ConfigChange>()

    override fun set(path: String, value: JsonElement) {
        changes += ConfigChange(keys(path), value)
    }

    override fun remove(path: String) {
        changes += ConfigChange(keys(path), null)
    }

    private fun keys(path: String): List<String> {
        val keys = path.split('.')
        if (keys.any(String::isEmpty)) {
            throw refused(
                ConfigEditRefusal.INVALID_PATH,
                "Cannot change the config at '$root.$path': a path is keys joined by dots, none of them empty.",
            )
        }
        if (keys.first() == PluginIds.ENABLED_KEY) {
            throw refused(
                ConfigEditRefusal.INVALID_PATH,
                "Cannot change the config at '$root.$path': only the operator switches a plugin on and off.",
            )
        }
        return keys
    }
}
