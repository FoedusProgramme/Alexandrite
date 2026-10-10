package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.runtime.i18n.TEXTS_SUFFIX
import org.foedusprogramme.alexandrite.runtime.i18n.readTexts
import org.foedusprogramme.alexandrite.runtime.i18n.resourceFiles
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.time.Duration

private val logger: Logger = LoggerFactory.getLogger("org.foedusprogramme.alexandrite.app")

/** Where the app's own language packs lie on its class path. */
internal const val APP_PACKS = "alexandrite/app-i18n"

/** Where the operator's texts lie beside the config file. */
internal const val OPERATOR_TEXTS = "i18n"

/**
 * The texts the app gives the plugins: its language [packs], overlaid by the operator's files below [directory], none
 * when it is null.
 */
internal class AppTexts(private val packs: TextCatalog, private val directory: Path?) {
    private val files = sortedMapOf<String, OperatorFile>()
    private val misnamed = mutableSetOf<String>()
    private val current = MutableStateFlow(packs)

    /** The texts as of the last [reload]. */
    val catalog: StateFlow<TextCatalog> = current.asStateFlow()

    /** Reads the operator's files again, updates [catalog] and returns the names of the files whose texts changed. */
    fun reload(): List<String> {
        val changed = changedFiles()
        if (changed.isNotEmpty()) {
            current.value = files.entries.fold(packs.toBuilder()) { builder, (name, file) ->
                val (plugin, language) = checkNotNull(parsedName(name))
                builder.texts(plugin, language, file.texts)
            }.build()
        }
        return changed
    }

    /** Reloads the operator's files every [interval]. */
    suspend fun watch(interval: Duration) {
        if (directory == null) return
        while (true) {
            delay(interval)
            val changed = runInterruptible(Dispatchers.IO) { reload() }
            if (changed.isNotEmpty()) logger.info("Texts reloaded from {}: {}", directory, changed.joinToString())
        }
    }

    /** The names of the operator's files whose texts changed since they were last read. */
    private fun changedFiles(): List<String> {
        val found = try {
            operatorFiles()
        } catch (e: IOException) {
            logger.warn("Cannot list the texts in {}, so they stay as they were: {}", directory, "$e")
            return emptyList()
        }
        val changed = files.keys.filterTo(mutableListOf()) { it !in found }
        files.keys.retainAll(found.keys)
        for ((name, path) in found) {
            val file = files.getOrPut(name, ::OperatorFile)
            val texts = try {
                val bytes = Files.readAllBytes(path)
                if (bytes.contentEquals(file.bytes)) continue
                file.bytes = bytes
                readTexts(bytes)
            } catch (e: IOException) {
                if (file.problem != "$e") {
                    logger.warn("Cannot read the texts {}, so its last good version stays: {}", name, "$e")
                }
                file.problem = "$e"
                continue
            }
            file.problem = null
            if (texts != file.texts) changed += name
            file.texts = texts
        }
        return changed.sorted()
    }

    /** The operator's text files by name. */
    private fun operatorFiles(): Map<String, Path> {
        if (directory == null || !directory.isDirectory()) return emptyMap()
        val found = sortedMapOf<String, Path>()
        for (plugin in listed(directory) { it.isDirectory() }) {
            for (path in listed(plugin) { it.isRegularFile() && it.name.endsWith(TEXTS_SUFFIX) }) {
                val name = "${plugin.name}/${path.name}"
                if (parsedName(name) != null) {
                    found[name] = path
                } else if (misnamed.add(name)) {
                    logger.warn("Ignoring the texts {} in {}: {}", name, directory, NAMING)
                }
            }
        }
        return found
    }

    /** The bytes of an operator's file as last read, its last good texts and the problem last warned about. */
    private class OperatorFile {
        var bytes: ByteArray? = null
        var texts: Map<String, String> = emptyMap()
        var problem: String? = null
    }

    companion object {
        /** The language packs below [directory] on [classLoader]. */
        fun packs(classLoader: ClassLoader, directory: String = APP_PACKS): TextCatalog {
            val builder = TextCatalog.builder()
            val roots = classLoader.getResources(directory).toList().distinctBy { it.toExternalForm() }
            val names = roots.flatMap { root ->
                try {
                    resourceFiles(root, directory, directory)
                } catch (e: IOException) {
                    logger.warn("Cannot list the app's texts in {}, which are left out: {}", root, "$e")
                    emptyList()
                }
            }
            for (name in names.distinct().sorted()) {
                val parsed = parsedName(name)
                if (parsed == null) {
                    logger.warn("Ignoring the app's texts {}: {}", name, NAMING)
                    continue
                }
                val url = checkNotNull(classLoader.getResource("$directory/$name"))
                try {
                    builder.texts(parsed.first, parsed.second, readTexts(url))
                } catch (e: IOException) {
                    logger.warn("Cannot read the app's texts {}, which are left out: {}", name, "$e")
                }
            }
            return builder.build()
        }
    }
}

/** The entries of [directory] that match [filter], by name. */
private fun listed(directory: Path, filter: (Path) -> Boolean): List<Path> = try {
    Files.list(directory).use { paths -> paths.filter(filter).sorted().toList() }
} catch (e: UncheckedIOException) {
    throw IOException(e.message, e)
}

private const val NAMING = "name them <plugin id>/<language tag>$TEXTS_SUFFIX, such as notes/zh-CN$TEXTS_SUFFIX"

/** The plugin and language of the text file [name], null when it is not `<plugin id>/<language tag>.properties`. */
private fun parsedName(name: String): Pair<String, LanguageTag>? {
    val plugin = name.substringBefore('/', "")
    val tag = name.substringAfter('/').removeSuffix(TEXTS_SUFFIX)
    if (!PluginIds.PATTERN.matches(plugin) || '/' in tag || !name.endsWith(TEXTS_SUFFIX)) return null
    val language = try {
        LanguageTag(tag)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return plugin to language
}
