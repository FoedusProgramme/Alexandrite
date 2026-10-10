package org.foedusprogramme.alexandrite.agent.project

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.i18n.Refused
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditException
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditor
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** A folder whose project instructions load, with those of the folders below it. */
internal class TrustedFolder(
    /** Canonical. */
    val path: Path,
    /** As the config file writes it. */
    val entry: String,
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
    paths: HostPaths,
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
        folders.filter { folder.startsWith(it.path) }.maxByOrNull { it.path.nameCount }

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
        writing.withLock {
            covering(canonical)?.let { return it.path }
            write(folders + TrustedFolder(canonical, canonical.toString(), by))
        }
        logger.info("Folder {} is trusted by {}", canonical, who(by))
        return canonical
    }

    /** Stops trusting [folder] for [by], false when it is not a trusted folder itself. */
    suspend fun untrust(folder: Path, by: ChatUser?): Boolean {
        val canonical = (check(folder) as? FloorCheck.Allowed)?.canonical ?: return false
        writing.withLock {
            val left = folders.filter { it.path != canonical }
            if (left.size == folders.size) return false
            write(left)
        }
        logger.info("Folder {} is no longer trusted, by {}", canonical, who(by))
        return true
    }

    private fun check(folder: Path): FloorCheck {
        if (!folder.isAbsolute) throw Refused(TextKeys.FOLDER_NOT_ABSOLUTE, "folder" to folder)
        return floor.check(folder)
    }

    /** Writes [after] into the config file, then applies it. */
    private suspend fun write(after: List<TrustedFolder>) {
        try {
            editor.edit {
                if (after.isEmpty()) remove(KEY) else set(KEY, JsonArray(after.map { JsonPrimitive(it.entry) }))
            }
        } catch (e: ConfigEditException) {
            logger.warn("The config file did not take a change of the trusted folders: {}", e.message)
            throw Refused(TextKeys.CONFIG_REFUSED, "reason" to e.refusal)
        }
        folders = after
    }
}

/**
 * The folders of [entries], relative ones resolved against the directory of the config file in [paths].
 *
 * Throws a [ConfigException] for a folder that [floor] refuses.
 */
private fun configured(entries: List<String>, paths: HostPaths, floor: HardFloor): List<TrustedFolder> =
    entries.map { entry ->
        val given = Path.of(entry)
        val path = if (given.isAbsolute) {
            given
        } else {
            paths.configFile?.parent?.resolve(given) ?: throw ConfigException(
                "agent.$KEY",
                "the trusted folder '$entry' is relative, but the host read no config file to resolve it against: " +
                    "give an absolute path",
            )
        }
        when (val check = floor.check(path.normalize())) {
            is FloorCheck.Allowed -> TrustedFolder(check.canonical, entry, null)

            is FloorCheck.Denied -> throw ConfigException(
                "agent.$KEY",
                "the trusted folder $path is a protected place (${check.reason}), which no file tool reaches",
            )
        }
    }

private fun who(by: ChatUser?): String = by?.address?.toString() ?: "the host"

private const val KEY = "trustedFolders"

private val logger = LoggerFactory.getLogger(FolderTrust::class.java)
