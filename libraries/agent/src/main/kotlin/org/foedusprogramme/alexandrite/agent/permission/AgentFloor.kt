package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.directory.ConversationDirectories
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.internal.floor.DenyRoot
import org.foedusprogramme.alexandrite.internal.floor.Floor
import org.foedusprogramme.alexandrite.internal.floor.FloorDecision
import org.foedusprogramme.alexandrite.internal.floor.Overlap
import org.foedusprogramme.alexandrite.internal.floor.Placement
import org.foedusprogramme.alexandrite.internal.path.PathCanonicalizer
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** The hard floor of every tool. */
@Singleton
@Binds(HardFloor::class)
internal class AgentFloor(
    paths: HostPaths,
    workspaces: Workspaces,
    settings: AgentSettings,
    private val directories: ConversationDirectories,
) : HardFloor {
    private val floor = hostFloor(
        paths,
        workspaces.paths,
        userHome(),
        operatorPlaces(paths, settings.protectedPaths),
        environment = System.getenv(),
    )

    override fun checkRead(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
        ownConversation(path, agent, conversation, tree = false) { floor.checkRead(path, agent) }

    override fun checkWrite(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
        ownConversation(path, agent, conversation, tree = false) { floor.checkWrite(path) }

    override fun checkTree(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
        ownConversation(path, agent, conversation, tree = true) { floor.checkTree(path) }

    /** [path] (absolute) made canonical and checked for a write by the persona tool of [agent]. */
    fun checkPersonaWrite(path: Path, agent: AgentId): FloorCheck = floor.checkPersonaWrite(path, agent).toCheck()

    /**
     * [base]'s decision, or the directory of [agent]'s conversation [conversation] carved out of the data root for
     * [path] instead, when [base] refuses it and carving that one directory out would not.
     */
    private fun ownConversation(
        path: Path,
        agent: AgentId?,
        conversation: ConversationId?,
        tree: Boolean,
        base: () -> FloorDecision,
    ): FloorCheck {
        val decision = base()
        if (decision !is FloorDecision.Denied || agent == null || conversation == null) return decision.toCheck()
        val exemption = floor.checkConversation(path, directories.path(agent, conversation), tree)
        return if (exemption is FloorDecision.Allowed) exemption.toCheck() else decision.toCheck()
    }

    private fun FloorDecision.toCheck(): FloorCheck = when (this) {
        is FloorDecision.Allowed -> FloorCheck.Allowed(canonical)
        is FloorDecision.Denied -> FloorCheck.Denied(reason)
    }
}

/** The floor of each agent, which lets that agent alone read its persona directory. */
internal class HostFloor(
    /** Null to refuse every path. */
    private val shared: Floor?,
    private val own: Map<AgentId, Floor>,
    private val personas: Map<AgentId, Path> = emptyMap(),
    /** [checkConversation], null to refuse every path. */
    private val withConversation: ((path: Path, directory: Path, tree: Boolean) -> FloorDecision)? = null,
) {
    fun checkRead(path: Path, agent: AgentId? = null): FloorDecision {
        val floor = agent?.let(own::get) ?: shared ?: return FloorDecision.Denied(UNKNOWN_HOME)
        return floor.check(path)
    }

    fun checkWrite(path: Path): FloorDecision = shared?.check(path) ?: FloorDecision.Denied(UNKNOWN_HOME)

    fun checkTree(path: Path): FloorDecision = shared?.checkTree(path) ?: FloorDecision.Denied(UNKNOWN_HOME)

    /** [path] checked as by [checkWrite] (or [checkTree] when [tree]) with [directory] carved out of the data root. */
    fun checkConversation(path: Path, directory: Path, tree: Boolean): FloorDecision =
        withConversation?.invoke(path, directory, tree) ?: FloorDecision.Denied(UNKNOWN_HOME)

    /** [path] checked for a write that only the Markdown files directly in [agent]'s persona directory may take. */
    fun checkPersonaWrite(path: Path, agent: AgentId): FloorDecision {
        if (shared == null) return FloorDecision.Denied(UNKNOWN_HOME)
        val floor = own[agent] ?: return FloorDecision.Denied(NO_PERSONA)
        val persona = personas[agent] ?: return FloorDecision.Denied(NO_PERSONA)
        val decision = floor.check(path)
        if (decision !is FloorDecision.Allowed) return decision
        val file = decision.canonical
        val directory = floor.check(persona)
        return when {
            directory !is FloorDecision.Allowed || file.parent != directory.canonical ->
                FloorDecision.Denied("a place outside the persona directory of agent '$agent'")

            !file.fileName.toString().endsWith(MARKDOWN, ignoreCase = true) -> FloorDecision.Denied(NOT_MARKDOWN)

            else -> decision
        }
    }
}

/**
 * The floor over the host's places in [paths], the [operator]'s places and the credential stores of [home] and
 * [environment], with each of [workspaces] carved out of the data root or the config directory that holds it, and each
 * agent's persona directory carved out for that agent alone.
 *
 * Throws a [ConfigException] for a workspace that is a protected place, holds one, or lies in one that keeps no
 * workspace.
 */
internal fun hostFloor(
    paths: HostPaths,
    workspaces: Map<AgentId, Path>,
    home: Path?,
    operator: List<Path> = emptyList(),
    canonicalizer: PathCanonicalizer = PathCanonicalizer(),
    environment: Map<String, String> = emptyMap(),
): HostFloor {
    val directory = paths.configFile?.parent
    val configuration = directory?.let { DenyRoot(it, CONFIGURATION) }
    val data = DenyRoot(paths.dataRoot, "Alexandrite's data")
    val places = listOfNotNull(configuration) +
        listOfNotNull(paths.configTarget, paths.configBackup).map { DenyRoot(it, CONFIGURATION) } +
        DenyRoot(paths.pluginDataRoot, "plugin data") +
        data +
        DenyRoot(paths.cacheRoot, "Alexandrite's cache") +
        paths.protected.map { DenyRoot(it, "a place the host protects") } +
        operator.map { DenyRoot(it, "a place the operator protects") }
    val keepsCarveOuts = setOfNotNull(configuration, data)
    val plain = Floor(places, home, canonicalizer, environment)
    val personas = workspaces.keys.mapNotNull { id -> personaDirectory(paths, id)?.let { id to it } }.toMap()
    val personaPlaces = Floor(
        personas.map { (id, persona) -> DenyRoot(persona, "the persona directory of agent '$id'") },
        null,
        canonicalizer,
    )
    val carveOuts = mutableMapOf<DenyRoot, MutableList<Path>>()
    for ((id, workspace) in workspaces) {
        val overlaps = plain.overlaps(workspace)
        val collision = overlaps.filter { it.placement != Placement.INSIDE || it.root !in keepsCarveOuts }
            .minByOrNull { it.placement }
            ?: personaPlaces.overlaps(workspace).filter { it.root != null }.minByOrNull { it.placement }
        collision?.let { throw ConfigException("agent.agents.$id.workspace", collision(workspace, it)) }
        overlaps.forEach { carveOuts.getOrPut(checkNotNull(it.root)) { mutableListOf() }.add(workspace) }
    }
    warnShared(workspaces, canonicalizer)
    if (home == null) {
        logger.error("The home directory is unknown: the hard floor refuses every path")
        return HostFloor(null, emptyMap())
    }
    plain.overlaps(home).firstOrNull { it.root == configuration && it.placement != Placement.HOLDS }?.let { overlap ->
        logger.warn(
            "The config file {} lies in {}, which is the home directory or holds it: the hard floor keeps the file " +
                "tools out of all of it. Keep the config file in a directory of its own",
            paths.configFile,
            checkNotNull(overlap.root).path,
        )
    }
    fun floor(extra: Map<DenyRoot, Path>) = Floor(
        places.map { DenyRoot(it.path, it.kind, carveOuts[it].orEmpty() + listOfNotNull(extra[it])) },
        home,
        canonicalizer,
        environment,
    )
    val own = personas.mapValues { (_, persona) ->
        floor(
            plain.overlaps(persona)
                .filter { it.placement == Placement.INSIDE && it.root in keepsCarveOuts }
                .associate { checkNotNull(it.root) to persona },
        )
    }
    val withConversation = { path: Path, conversationDirectory: Path, tree: Boolean ->
        val extended = floor(mapOf(data to conversationDirectory))
        if (tree) extended.checkTree(path) else extended.check(path)
    }
    return HostFloor(floor(emptyMap()), own, personas, withConversation)
}

/** The persona directory of [agent] beside the config file in [paths], null when the host read no config file. */
internal fun personaDirectory(paths: HostPaths, agent: AgentId): Path? =
    paths.configFile?.parent?.resolve(PERSONAS)?.resolve(agent.value)?.normalize()

/** [agent]'s persona directory, created owner-only when it does not exist, null when the host read no config file. */
internal fun openPersonaDirectory(paths: HostPaths, agent: AgentId): Path? {
    val directory = personaDirectory(paths, agent) ?: return null
    if (Files.isDirectory(directory)) return directory
    if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER)))
    } else {
        Files.createDirectories(directory)
    }
    return directory
}

/** The places of `agent.protectedPaths`, each absolute or relative to the config file's directory. */
internal fun operatorPlaces(paths: HostPaths, configured: List<String>): List<Path> = configured.map { entry ->
    val path = Path.of(entry)
    if (path.isAbsolute) return@map path.normalize()
    val directory = paths.configFile?.parent ?: throw ConfigException(
        "agent.protectedPaths",
        "the protected path '$entry' is relative, but the host read no config file to resolve it against: give an " +
            "absolute path",
    )
    directory.resolve(path).normalize()
}

private fun collision(workspace: Path, overlap: Overlap): String {
    val relation = when (overlap.placement) {
        Placement.AT -> "is"
        Placement.INSIDE -> "lies in"
        Placement.HOLDS -> "holds"
    }
    return "the workspace $workspace $relation a protected place (${overlap.kind}): give the agent another directory"
}

private fun warnShared(workspaces: Map<AgentId, Path>, canonicalizer: PathCanonicalizer) {
    val agents = workspaces.entries.groupBy({ canonicalizer.canonicalize(it.value).real }, { it.key })
    for ((workspace, ids) in agents) {
        if (ids.size < 2) continue
        logger.warn(
            "Agents {} share the workspace {}: they see each other's files",
            ids.joinToString { "'$it'" },
            workspace,
        )
    }
}

private fun userHome(): Path? = try {
    System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.takeIf { it.isAbsolute }
} catch (e: InvalidPathException) {
    null
}

private const val CONFIGURATION = "Alexandrite's configuration"

/** Where the agents' persona directories lie in the config directory. */
private const val PERSONAS = "agents"

private const val OWNER = "rwx------"

private const val UNKNOWN_HOME = "every path, as the home directory is unknown"

private const val NO_PERSONA = "every path, as the agent has no persona directory"

private const val NOT_MARKDOWN = "a persona file that is no Markdown file"

private const val MARKDOWN = ".md"

private val logger = LoggerFactory.getLogger(AgentFloor::class.java)
