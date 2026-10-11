package org.foedusprogramme.alexandrite.agent.project

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.i18n.Refused
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditException
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditor
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.config.ConfigLiteral
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A folder whose project instructions load, with those below it outside the repositories nested in it. */
internal class TrustedFolder(
    /** Canonical. */
    val path: Path,
    /** Who trusted the folder at runtime, null for the config. */
    val by: ChatUser?,
)

/**
 * The trusted folders of the config and those trusted at runtime, read without suspending.
 *
 * A change is written into the config file before it applies, and one that breaks a rule throws [Refused].
 */
@Singleton
internal class FolderTrust(
    settings: AgentSettings,
    private val paths: HostPaths,
    private val floor: HardFloor,
    private val editor: ConfigEditor,
) {
    /** Held from checking a change until it is applied. */
    private val writing = Mutex()

    @Volatile
    private var folders: List<TrustedFolder> = configured(settings.trustedFolders, paths, floor)

    /** The trusted folders, each once, in the order they were trusted. */
    fun folders(): List<Path> = folders.map { it.path }.distinct()

    /** The trusted folder nearest to [folder] (canonical) that is it or holds it, null when none does. */
    fun covering(folder: Path): TrustedFolder? =
        folders.filter { covers(it.path, folder) }.maxByOrNull { it.path.nameCount }

    /** Whether [folder] (canonical) is a trusted folder itself. */
    fun isTrusted(folder: Path): Boolean = folders.any { it.path == folder }

    /** Trusts [folder] for [by] and returns the trusted folder that covers it. */
    suspend fun trust(folder: Path, by: ChatUser?): Path {
        val canonical = when (val check = check(folder)) {
            is FloorCheck.Allowed -> check.canonical

            is FloorCheck.Denied ->
                throw Refused(TextKeys.FOLDER_PROTECTED, "folder" to folder, "reason" to check.reason)
        }
        if (!Files.isDirectory(canonical)) throw Refused(TextKeys.FOLDER_MISSING, "folder" to folder)
        val cover = writing.withLock {
            covering(canonical)?.let { return it.path }
            var found: Path? = null
            edit { entries ->
                found = entries.mapNotNull(::folderOf).filter { covers(it, canonical) }.maxByOrNull { it.nameCount }
                if (found == null) entries + ConfigLiteral.Text("$canonical") else entries
            }
            folders = folders + (found?.let { TrustedFolder(it, null) } ?: TrustedFolder(canonical, by))
            found
        }
        if (cover == null) {
            logger.info("Folder {} is trusted by {}", canonical, who(by))
            return canonical
        }
        logger.warn("Folder {} is covered by {}, which the config file trusts since a hand edit", canonical, cover)
        return cover
    }

    /** Stops trusting [folder] for [by], false when it is not a trusted folder itself. */
    suspend fun untrust(folder: Path, by: ChatUser?): Boolean {
        val canonical = (check(folder) as? FloorCheck.Allowed)?.canonical ?: return false
        writing.withLock {
            val configured = folders.any { it.path == canonical && it.by == null }
            var removed = false
            edit { entries ->
                val left = entries.filter { folderOf(it) != canonical }
                removed = left.size < entries.size
                if (!removed && configured && entries.any { folderOf(it) == null }) {
                    throw Refused(TextKeys.FOLDER_BY_REFERENCE, "folder" to canonical)
                }
                left
            }
            if (!removed && !isTrusted(canonical)) return false
            folders = folders.filter { it.path != canonical }
        }
        logger.info("Folder {} is no longer trusted, by {}", canonical, who(by))
        return true
    }

    private fun check(folder: Path): FloorCheck {
        if (!folder.isAbsolute) throw Refused(TextKeys.FOLDER_NOT_ABSOLUTE, "folder" to folder)
        return floor.check(folder)
    }

    /** Writes the entries that [change] makes of the config file's entries as the file holds them. */
    private suspend fun edit(change: (List<ConfigLiteral>) -> List<ConfigLiteral>) {
        try {
            editor.edit {
                val entries = (getLiteral(KEY) as? ConfigLiteral.Items)?.items.orEmpty()
                val after = change(entries)
                if (after != entries) if (after.isEmpty()) remove(KEY) else setLiteral(KEY, ConfigLiteral.Items(after))
            }
        } catch (e: ConfigEditException) {
            logger.warn("The config file did not take a change of the trusted folders: {}", e.message)
            throw Refused(TextKeys.CONFIG_REFUSED, "reason" to e.refusal)
        }
    }

    /** The canonical folder of the config file's [entry], null when it names an environment variable. */
    private fun folderOf(entry: ConfigLiteral): Path? {
        val path = (entry as? ConfigLiteral.Text)?.let { absolute(it.text, paths) } ?: return null
        return (floor.check(path) as? FloorCheck.Allowed)?.canonical ?: path
    }
}

/**
 * The folders of [entries], relative ones resolved against the directory of the config file in [paths].
 *
 * Throws a [ConfigException] for a folder that [floor] refuses.
 */
private fun configured(entries: List<String>, paths: HostPaths, floor: HardFloor): List<TrustedFolder> =
    entries.map { entry ->
        val path = absolute(entry, paths) ?: throw ConfigException(
            "agent.$KEY",
            "the trusted folder '$entry' is relative, but the host read no config file to resolve it against: " +
                "give an absolute path",
        )
        when (val check = floor.check(path)) {
            is FloorCheck.Allowed -> TrustedFolder(check.canonical, null)

            is FloorCheck.Denied -> throw ConfigException(
                "agent.$KEY",
                "the trusted folder $path is a protected place (${check.reason}), which no file tool reaches",
            )
        }
    }

/** Whether [folder] is [trusted] or lies in it outside every repository nested in it. */
private fun covers(trusted: Path, folder: Path): Boolean = folder.startsWith(trusted) &&
    generateSequence(folder) { it.parent }.takeWhile { it != trusted }.none(::isRepository)

/** [entry] made absolute against the directory of the config file in [paths], null when it cannot be. */
private fun absolute(entry: String, paths: HostPaths): Path? {
    val given = try {
        Path.of(entry)
    } catch (e: InvalidPathException) {
        return null
    }
    return (if (given.isAbsolute) given else paths.configFile?.parent?.resolve(given))?.normalize()
}

private fun who(by: ChatUser?): String = by?.address?.toString() ?: "the host"

private const val KEY = "trustedFolders"

private val logger = LoggerFactory.getLogger(FolderTrust::class.java)
