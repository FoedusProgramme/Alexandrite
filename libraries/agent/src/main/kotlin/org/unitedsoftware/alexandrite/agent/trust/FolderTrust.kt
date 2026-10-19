package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.config.configuredPath
import org.unitedsoftware.alexandrite.agent.control.Granter
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.permission.canonical
import org.unitedsoftware.alexandrite.agent.project.isRepository
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
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

            is FloorCheck.Ask -> check.canonical

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

    /**
     * Stops trusting [folder] at runtime for [by], removing each grant of it as named or as it is canonical today, and
     * tells whether the config still trusts it.
     */
    suspend fun untrust(folder: Path, by: ChatUser?): Revocation {
        val canonical = check(folder).canonical
        val revocation = writing.withLock {
            val removed = grants.list().filter { isGrantOf(it.key, folder, canonical) }.count { grants.remove(it.key) }
            val trusted = folders.firstOrNull { it.path == canonical }
            if (trusted != null) {
                val left = TrustedFolder(trusted.path, true, null, null).takeIf { trusted.declared }
                folders = folders.mapNotNull { if (it === trusted) left else it }
            }
            Revocation(removed > 0 || trusted?.declared == false, trusted?.declared == true)
        }
        when {
            revocation.declared && revocation.removed ->
                logger.info("The grant of folder {} is removed by {}: the config still trusts it", folder, who(by))

            revocation.removed -> logger.info("Folder {} is no longer trusted, by {}", folder, who(by))
        }
        return revocation
    }

    private fun check(folder: Path): FloorCheck {
        if (!folder.isAbsolute) throw Refused(TextKeys.FOLDER_NOT_ABSOLUTE, "folder" to folder)
        return floor.checkRead(folder)
    }

    /** Whether the grant [key] is of [folder], as named or as the folder is canonical today, at [canonical]. */
    private fun isGrantOf(key: String, folder: Path, canonical: Path?): Boolean {
        val path = grantedPath(key) ?: return false
        if (path.normalize() == folder.normalize()) return true
        if (canonical == null) return false
        return path == canonical || floor.checkRead(path).canonical == canonical
    }

    /** The folder that [grant] trusts, null when the floor now refuses it. */
    private fun granted(grant: StoredRecord<FolderGrant>): TrustedFolder? {
        val path = grantedPath(grant.key)
        val check = path?.let(floor::checkRead)
        val canonical = check?.canonical
        if (canonical == null) {
            val reason = (check as? FloorCheck.Denied)?.reason ?: "no absolute path"
            logger.warn("The grant of folder {} is ignored: {}", grant.key, reason)
            return null
        }
        return TrustedFolder(canonical, false, grant.createdAt, grant.value.by?.user())
    }
}

/** A folder trusted at runtime. */
@Serializable
private class FolderGrant(val by: Granter? = null)

/** The absolute path of the grant [key], null when it names none. */
private fun grantedPath(key: String): Path? = try {
    Path.of(key).takeIf { it.isAbsolute }
} catch (e: InvalidPathException) {
    null
}

/**
 * The folders of [entries], relative ones resolved against the directory of the config file in [paths].
 *
 * Throws a [ConfigException] for a folder that [floor] refuses.
 */
private fun configured(entries: List<String>, paths: HostPaths, floor: HardFloor): List<Path> = entries.map { entry ->
    val path = configuredPath(paths, "agent.$KEY", "the trusted folder", entry)
    when (val check = floor.checkRead(path)) {
        is FloorCheck.Allowed -> check.canonical

        is FloorCheck.Ask -> check.canonical

        is FloorCheck.Denied -> throw ConfigException(
            "agent.$KEY",
            "the trusted folder $path is a protected place (${check.reason}), which no file tool reaches",
        )
    }
}

/** Whether [folder] is [trusted] or lies in it outside every repository nested in it. */
private fun covers(trusted: Path, folder: Path): Boolean = folder.startsWith(trusted) &&
    generateSequence(folder) { it.parent }.takeWhile { it != trusted }.none(::isRepository)

private const val KEY = "trustedFolders"

private const val COLLECTION = "trusted_folders"

private val logger = LoggerFactory.getLogger(FolderTrust::class.java)
