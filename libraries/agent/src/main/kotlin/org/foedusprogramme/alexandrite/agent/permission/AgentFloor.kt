package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.internal.floor.DenyRoot
import org.foedusprogramme.alexandrite.internal.floor.Floor
import org.foedusprogramme.alexandrite.internal.floor.FloorDecision
import org.foedusprogramme.alexandrite.internal.floor.Overlap
import org.foedusprogramme.alexandrite.internal.floor.Placement
import org.foedusprogramme.alexandrite.internal.path.PathCanonicalizer
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.slf4j.LoggerFactory
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The hard floor of every tool. */
@Singleton
@Binds(HardFloor::class)
internal class AgentFloor(paths: HostPaths, workspaces: Workspaces, settings: AgentSettings) : HardFloor {
    private val floor = hostFloor(paths, workspaces.paths, userHome(), operatorPlaces(paths, settings.protectedPaths))

    override fun checkRead(path: Path, agent: AgentId?): FloorCheck = when (val decision = floor.check(path, agent)) {
        is FloorDecision.Allowed -> FloorCheck.Allowed(decision.canonical)
        is FloorDecision.Denied -> FloorCheck.Denied(decision.kind)
    }
}

/** The floor of each agent, which lets that agent alone read its persona directory. */
internal class HostFloor(
    /** Null to refuse every path. */
    private val shared: Floor?,
    private val own: Map<AgentId, Floor>,
) {
    fun check(path: Path, agent: AgentId? = null): FloorDecision {
        val floor = agent?.let(own::get) ?: shared ?: return FloorDecision.Denied(UNKNOWN_HOME)
        return floor.check(path)
    }
}

/**
 * The floor over the host's places in [paths], the [operator]'s places and the credential stores of [home], with each
 * of [workspaces] carved out of the data root or the config directory that holds it, and each agent's persona
 * directory carved out for that agent alone.
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
): HostFloor {
    val directory = paths.configFile?.parent
    val configurations = listOfNotNull(directory, paths.configTarget?.parent).distinct()
        .map { DenyRoot(it, "Alexandrite's configuration") }
    val data = DenyRoot(paths.dataRoot, "Alexandrite's data")
    val places = configurations +
        DenyRoot(paths.pluginDataRoot, "plugin data") +
        data +
        DenyRoot(paths.cacheRoot, "Alexandrite's cache") +
        listOfNotNull(paths.configBackup).map { DenyRoot(it, "Alexandrite's configuration") } +
        paths.protected.map { DenyRoot(it, "a place the host protects") } +
        operator.map { DenyRoot(it, "a place the operator protects") }
    val keepsCarveOuts = configurations.toSet() + data
    val plain = Floor(places, home, canonicalizer)
    val personas = directory?.let { workspaces.keys.associateWith { id -> it.resolve(PERSONAS).resolve(id.value) } }
        .orEmpty()
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
    plain.overlaps(home).firstOrNull { it.root in configurations && it.placement != Placement.HOLDS }?.let { overlap ->
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
    )
    val own = personas.mapValues { (_, persona) ->
        floor(
            plain.overlaps(persona)
                .filter { it.placement == Placement.INSIDE && it.root in keepsCarveOuts }
                .associate { checkNotNull(it.root) to persona },
        )
    }
    return HostFloor(floor(emptyMap()), own)
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

/** Where the agents' persona directories lie in the config directory. */
private const val PERSONAS = "agents"

private const val UNKNOWN_HOME = "every path, as the home directory is unknown"

private val logger = LoggerFactory.getLogger(AgentFloor::class.java)
