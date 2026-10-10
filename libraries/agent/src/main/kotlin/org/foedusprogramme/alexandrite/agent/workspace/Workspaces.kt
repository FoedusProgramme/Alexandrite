package org.foedusprogramme.alexandrite.agent.workspace

import org.foedusprogramme.alexandrite.agent.config.AgentConfig
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** The directories where the agents keep their own files. */
@Singleton
internal class Workspaces(paths: HostPaths, settings: AgentSettings) {
    /** Absolute and normalized, in config order. */
    val paths: Map<AgentId, Path> = agentWorkspaces(paths, settings.agents)

    /** The workspace of [agent], created owner-only when it does not exist, null for an agent that is not configured. */
    fun open(agent: AgentId): Path? {
        val path = paths[agent] ?: return null
        if (Files.isDirectory(path)) return path
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER)))
        } else {
            Files.createDirectories(path)
        }
        logger.info("Created the workspace {} of agent '{}'", path, agent)
        return path
    }
}

/**
 * Where each of [agents] keeps its own files: its configured workspace, relative to the config file's directory, or
 * `<data root>/workspaces/<id>`.
 */
internal fun agentWorkspaces(paths: HostPaths, agents: Map<String, AgentConfig>): Map<AgentId, Path> =
    agents.entries.associate { (id, config) -> AgentId(id) to workspace(id, config, paths) }

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

private const val WORKSPACES = "workspaces"

private const val OWNER = "rwx------"

private val logger = LoggerFactory.getLogger(Workspaces::class.java)
