package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import java.nio.file.Path

/** [value] set at [path] below a plugin's config root, or that member removed when [value] is null. */
internal class ConfigChange(val path: List<String>, val value: JsonElement?)

/** The config file as an edit found it. */
internal class OpenFile(val target: Path, val bytes: ByteArray, val text: String)

/** Writes the plugins' changes into [file], whose `${env:NAME}` references name variables of [environment]. */
internal class ConfigWriter(
    private val file: Path?,
    private val environment: () -> Map<String, String>,
    /** The runtime's name in log lines. */
    private val name: String,
) {
    /** Runs [block] of [plugin] on the file as it is now and writes its changes, or throws [ConfigEditException]. */
    suspend fun edit(plugin: EnabledPlugin, block: ConfigEdit.() -> Unit) {
        lockedConfig {
            val edit = PluginEdit(plugin.member.index.configRoot) { open(plugin) }
            edit.block()
            edit.file?.let { write(plugin, it, edit.text, edit.changes) }
        }
    }

    private fun open(plugin: EnabledPlugin): OpenFile {
        val opened = openConfig(file)
        readable(ConfigEditRefusal.INVALID_FILE, "The config file does not read as it is", opened.target) {
            decoded(plugin, opened.target, opened.text)
        }
        return opened
    }

    private fun write(plugin: EnabledPlugin, file: OpenFile, edited: ConfigText, changes: List<ConfigChange>) {
        val target = file.target
        if (edited.text == file.text) return
        val written = readable(ConfigEditRefusal.INVALID_RESULT, "The change would break the config file", target) {
            decoded(plugin, target, edited.text)
        }
        checkSecrets(plugin.member.index, plugin.channelType, written, changes)
        replaceConfig(file, edited.text)
        val root = plugin.member.index.configRoot
        val paths = changes.map { (listOf(root) + it.path).joinToString(".") }.distinct()
        logger.info("{}: plugin {} changed {} in the config file {}", name, plugin.id, paths.joinToString(), target)
    }

    /** The raw object of [text], once it reads as the host reads it and [plugin]'s sections decode from it. */
    private fun decoded(plugin: EnabledPlugin, target: Path, text: String): JsonObject {
        val raw = ConfigFile.parsed(target, text)
        val source = JsonConfigSource(ConfigFile.resolved(target, raw, environment()))
        disabledReason(plugin.member, source)
        configBindings(plugin.member.index, plugin.channelType, source, mutableListOf())
        return raw
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)
    }
}

/** Where the writer keeps the previous text of the config file [target]. */
internal fun configBackup(target: Path): Path = target.resolveSibling("${target.fileName}.bak")

internal fun refused(refusal: ConfigEditRefusal, message: String, cause: Throwable? = null): ConfigEditException =
    ConfigEditException(refusal, message, cause)
