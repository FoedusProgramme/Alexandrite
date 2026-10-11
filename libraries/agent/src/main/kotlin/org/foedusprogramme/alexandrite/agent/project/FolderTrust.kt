package org.foedusprogramme.alexandrite.agent.project

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.i18n.Refused
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.store.Records
import org.foedusprogramme.alexandrite.sdk.store.StoredRecord
import org.foedusprogramme.alexandrite.sdk.store.collection
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.sdk.turn.TrustedFolder
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The trusted folders that the config declares and those granted at runtime, read without suspending.
 *
 * A change that breaks a rule throws [Refused].
 */
@Singleton
internal class FolderTrust(settings: AgentSettings, paths: HostPaths, private val floor: HardFloor, records: Records) :
    Lifecycle {
    private val grants = records.collection<FolderGrant>(COLLECTION)

    /** Held from checking a change until it is applied. */
    private val writing = Mutex()

    private val declared: List<Path> = configured(settings.trustedFolders, paths, floor).distinct()

    @Volatile
    private var folders: List<TrustedFolder> = declared.map { TrustedFolder(it, true, null, null) }

    override suspend fun onStart() {
        val granted = grants.list().mapNotNull(::granted).associateBy { it.path }
        writing.withLock {
            folders = declared.map { path ->
                granted[path]?.let { TrustedFolder(path, true, it.trustedAt, it.trustedBy) }
                    ?: TrustedFolder(path, true, null, null)
            } + granted.values.filter { it.path !in declared }
        }
    }

    /** The trusted folders, the declared ones first. */
    fun folders(): List<TrustedFolder> = folders

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
        writing.withLock {
            covering(canonical)?.let { return it.path }
            val grant = grants.put("$canonical", FolderGrant(by?.let(::Granter)), by?.address)
            folders = folders + TrustedFolder(canonical, false, grant.createdAt, by)
        }
        logger.info("Folder {} is trusted by {}", canonical, who(by))
        return canonical
    }

    /** Stops trusting [folder] for [by], false when it is not a trusted folder itself. */
    suspend fun untrust(folder: Path, by: ChatUser?): Boolean {
        val canonical = (check(folder) as? FloorCheck.Allowed)?.canonical ?: return false
        writing.withLock {
            val trusted = folders.firstOrNull { it.path == canonical } ?: return false
            if (trusted.declared) throw Refused(TextKeys.FOLDER_DECLARED, "folder" to canonical)
            grants.remove("$canonical")
            folders = folders - trusted
        }
        logger.info("Folder {} is no longer trusted, by {}", canonical, who(by))
        return true
    }

    private fun check(folder: Path): FloorCheck {
        if (!folder.isAbsolute) throw Refused(TextKeys.FOLDER_NOT_ABSOLUTE, "folder" to folder)
        return floor.checkRead(folder)
    }

    /** The folder that [grant] trusts, null when the floor now refuses it. */
    private fun granted(grant: StoredRecord<FolderGrant>): TrustedFolder? {
        val path = try {
            Path.of(grant.key).takeIf { it.isAbsolute }
        } catch (e: InvalidPathException) {
            null
        }
        val check = path?.let(floor::checkRead)
        if (check !is FloorCheck.Allowed) {
            val reason = (check as? FloorCheck.Denied)?.reason ?: "no absolute path"
            logger.warn("The grant of folder {} is ignored: {}", grant.key, reason)
            return null
        }
        return TrustedFolder(check.canonical, false, grant.createdAt, grant.value.by?.user())
    }
}

/** A folder trusted at runtime. */
@Serializable
private class FolderGrant(val by: Granter? = null)

/** Who trusted a folder, as their channel saw them then. */
@Serializable
private class Granter(
    val address: UserAddress,
    val name: String,
    val username: String? = null,
    val bot: Boolean = false,
    val admin: Boolean = false,
) {
    constructor(user: ChatUser) : this(user.address, user.displayName, user.username, user.isBot, user.isAdmin)

    fun user(): ChatUser = ChatUser(address, name, username, bot, admin)
}

/**
 * The folders of [entries], relative ones resolved against the directory of the config file in [paths].
 *
 * Throws a [ConfigException] for a folder that [floor] refuses.
 */
private fun configured(entries: List<String>, paths: HostPaths, floor: HardFloor): List<Path> = entries.map { entry ->
    val path = absolute(entry, paths) ?: throw ConfigException(
        "agent.$KEY",
        "the trusted folder '$entry' is relative, but the host read no config file to resolve it against: " +
            "give an absolute path",
    )
    when (val check = floor.checkRead(path)) {
        is FloorCheck.Allowed -> check.canonical

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

private const val COLLECTION = "trusted_folders"

private val logger = LoggerFactory.getLogger(FolderTrust::class.java)
