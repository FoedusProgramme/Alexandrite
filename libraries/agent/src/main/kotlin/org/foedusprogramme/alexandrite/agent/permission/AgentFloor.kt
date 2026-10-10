package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.agent.config.AgentConfig
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.internal.floor.DenyRoot
import org.foedusprogramme.alexandrite.internal.floor.Floor
import org.foedusprogramme.alexandrite.internal.floor.FloorDecision
import org.foedusprogramme.alexandrite.internal.floor.Overlap
import org.foedusprogramme.alexandrite.internal.floor.Placement
import org.foedusprogramme.alexandrite.internal.path.PathCanonicalizer
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
internal class AgentFloor(paths: HostPaths, settings: AgentSettings) : HardFloor {
    private val floor = hostFloor(paths, settings.agents, userHome())

    override fun check(path: Path): FloorCheck = when (val decision = floor.check(path)) {
        is FloorDecision.Allowed -> FloorCheck.Allowed(decision.canonical)
        is FloorDecision.Denied -> FloorCheck.Denied(decision.kind)
    }
}

/**
 * The floor over the host's places in [paths] and the credential stores of [home], with each agent's workspace carved
 * out of the data root or the config directory that holds it.
 *
 * Throws a [ConfigException] for a workspace that is a protected place, holds one, or lies in one that keeps no
 * workspace.
 */
internal fun hostFloor(
    paths: HostPaths,
    agents: Map<String, AgentConfig>,
    home: Path?,
    canonicalizer: PathCanonicalizer = PathCanonicalizer(),
): Floor {
    val configuration = paths.configFile?.parent?.let { DenyRoot(it, "Alexandrite's configuration") }
    val data = DenyRoot(paths.dataRoot, "Alexandrite's data")
    val places = listOfNotNull(configuration) +
        DenyRoot(paths.dataRoot.resolve(PLUGINS), "plugin data") +
        data +
        DenyRoot(paths.cacheRoot, "Alexandrite's cache") +
        paths.protected.map { DenyRoot(it, "a place the host protects") }
    val keepsWorkspaces = setOfNotNull(configuration, data)
    val plain = Floor(places, home, canonicalizer)
    val workspaces = agents.mapValues { (id, config) -> workspace(id, config, paths) }
    val carveOuts = mutableMapOf<DenyRoot, MutableList<Path>>()
    for ((id, workspace) in workspaces) {
        val overlaps = plain.overlaps(workspace)
        overlaps.filter { it.placement != Placement.INSIDE || it.root !in keepsWorkspaces }
            .minByOrNull { it.placement }
            ?.let { throw ConfigException("agent.agents.$id.workspace", collision(workspace, it)) }
        overlaps.forEach { carveOuts.getOrPut(checkNotNull(it.root)) { mutableListOf() }.add(workspace) }
    }
    warnShared(workspaces, canonicalizer)
    return Floor(places.map { DenyRoot(it.path, it.kind, carveOuts[it].orEmpty()) }, home, canonicalizer)
}

/** Where agent [id] keeps its own files. */
private fun workspace(id: String, config: AgentConfig, paths: HostPaths): Path {
    val configured = config.workspace ?: return paths.dataRoot.resolve(WORKSPACES).resolve(id)
    val path = Path.of(configured)
    if (path.isAbsolute) return path.normalize()
    val directory = paths.configFile?.parent ?: throw ConfigException(
        "agent.agents.$id.workspace",
        "the workspace '$configured' is relative, but the host read no config file to resolve it against: give an " +
            "absolute path",
    )
    return directory.resolve(path).normalize()
}

private fun collision(workspace: Path, overlap: Overlap): String {
    val relation = when (overlap.placement) {
        Placement.AT -> "is"
        Placement.INSIDE -> "lies in"
        Placement.HOLDS -> "holds"
    }
    return "the workspace $workspace $relation a protected place (${overlap.kind}): give the agent another directory"
}

private fun warnShared(workspaces: Map<String, Path>, canonicalizer: PathCanonicalizer) {
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

private fun userHome(): Path? {
    val home = try {
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.takeIf { it.isAbsolute }
    } catch (e: InvalidPathException) {
        null
    }
    if (home == null) logger.warn("The home directory is unknown: the hard floor cannot protect its credential stores")
    return home
}

private const val PLUGINS = "plugins"
private const val WORKSPACES = "workspaces"

private val logger = LoggerFactory.getLogger(AgentFloor::class.java)
