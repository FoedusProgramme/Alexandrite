package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.ConfigFileException
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.runtime.plugin.pluginProblems
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The host's config [file], whose changes must pass the stages PLUGINS and CONFIG of a runtime of [plugins]. */
public class HostConfigFile(
    private val file: Path?,
    private val plugins: PluginSet,
    /** Reads the variables that each change resolves its references with. */
    private val environment: () -> Map<String, String>,
    /** The runtime's name in log lines. */
    private val name: String = "alexandrite",
) : HostConfig {
    override suspend fun get(path: String): JsonElement? = lockedConfig {
        val configured = file ?: throw refused(ConfigEditRefusal.NO_FILE, "The host names no config file.")
        val text = try {
            Files.readString(configured)
        } catch (e: NoSuchFileException) {
            throw refused(ConfigEditRefusal.NO_FILE, "The config file $configured does not exist.", e)
        } catch (e: IOException) {
            throw refused(ConfigEditRefusal.IO_ERROR, "Cannot read the config file $configured: $e", e)
        }
        readable(ConfigEditRefusal.INVALID_FILE, "The config file does not read", configured) {
            ConfigFile.parsed(configured, text)
        }
        PluginEdit("") { OpenFile(configured, ByteArray(0), text) }.get(path)
    }

    override suspend fun edit(block: ConfigEdit.() -> Unit): ConfigChanges = lockedConfig {
        val edit = PluginEdit("") {
            openConfig(file).also { opened ->
                readable(ConfigEditRefusal.INVALID_FILE, "The config file does not read as it is", opened.target) {
                    ConfigFile.parsed(opened.target, opened.text)
                }
            }
        }
        edit.block()
        edit.file?.let { opened -> write(opened, edit.text.text, edit.changes.map { it.path }.distinct()) } ?: NONE
    }

    override suspend fun replace(text: String): ConfigChanges = lockedConfig { write(openConfig(file), text, null) }

    /** Writes [text] over [file], with the keys of each change as [paths], null for a change of the whole file. */
    private fun write(file: OpenFile, text: String, paths: List<List<String>>?): ConfigChanges {
        if (text == file.text) return NONE
        val target = file.target
        val what = "The change would break the config file"
        val written = readable(ConfigEditRefusal.INVALID_RESULT, what, target) { ConfigFile.parsed(target, text) }
        val resolved = readable(ConfigEditRefusal.INVALID_RESULT, what, target) {
            ConfigFile.resolved(target, written, environment())
        }
        val problems = pluginProblems(plugins).ifEmpty {
            resolveConfig(plugins.members, JsonConfigSource(resolved), mutableListOf()).problems
        }
        if (problems.isNotEmpty()) {
            throw refused(
                ConfigEditRefusal.INVALID_RESULT,
                "$what: ${problems.joinToString(" ") { it.message }}",
            )
        }
        val previous = try {
            ConfigFile.parsed(target, file.text)
        } catch (e: ConfigFileException) {
            JsonObject(emptyMap())
        }
        val changed = paths?.filter { previous.at(it) != written.at(it) }
            ?: mutableListOf<List<String>>().also { changedPaths(emptyList(), previous, written, it) }
        if (changed.isEmpty()) return NONE
        val set = changed.filter { written.at(it) != null }
        for (plugin in resolveConfig(plugins.members, JsonConfigSource(resolved), mutableListOf()).enabled) {
            val root = plugin.member.index.configRoot.split('.')
            val below = set.mapNotNull { keys ->
                when {
                    keys.size > root.size && keys.take(root.size) == root -> keys.drop(root.size)
                    root.take(keys.size) == keys -> emptyList()
                    else -> null
                }
            }
            if (below.isEmpty()) continue
            checkSecrets(plugin.member.index, plugin.channelType, written, below.map { ConfigChange(it, written) })
        }
        replaceConfig(file, text)
        val dotted = changed.map { it.joinToString(".") }
        logger.info("{}: changed {} in the config file {}", name, dotted.joinToString(), target)
        return ConfigChanges(dotted, dotted)
    }

    private companion object {
        val NONE = ConfigChanges(emptyList(), emptyList())

        val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)
    }
}

/** The value at [keys], null when there is none. */
private fun JsonObject.at(keys: List<String>): JsonElement? =
    keys.fold(this as JsonElement?) { element, key -> (element as? JsonObject)?.get(key) }

/** Adds the keys below [keys] whose values differ from [before] to [after] to [changed], whole where one is new. */
private fun changedPaths(
    keys: List<String>,
    before: JsonElement?,
    after: JsonElement?,
    changed: MutableList<List<String>>,
) {
    if (before == after) return
    if (before !is JsonObject || after !is JsonObject) {
        changed += keys
        return
    }
    for (key in after.keys + (before.keys - after.keys)) changedPaths(keys + key, before[key], after[key], changed)
}
