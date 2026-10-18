package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.runtime.AgentPlaces
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import java.nio.file.Path

/**
 * The places of a host that runs agents in the background: workspaces and conversations under the data directory,
 * personas beside the config file, which a host that reads none keeps none of.
 */
public class ConventionalAgentPlaces(private val paths: HostPaths) : AgentPlaces {
    override fun workspace(agent: AgentId): Path = paths.dataRoot.resolve(WORKSPACES).resolve(agent.value)

    override fun personaDirectory(agent: AgentId): Path? =
        paths.configFile?.parent?.resolve(PERSONAS)?.resolve(agent.value)?.normalize()

    override fun conversations(agent: AgentId): Path = paths.dataRoot.resolve(CONVERSATIONS).resolve(agent.value)

    private companion object {
        const val WORKSPACES = "workspaces"
        const val PERSONAS = "agents"
        const val CONVERSATIONS = "conversations"
    }
}
