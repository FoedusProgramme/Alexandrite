package org.foedusprogramme.alexandrite.agent.tool

import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.slf4j.LoggerFactory

/** The contributed tools by name, and the ones each agent selects. */
@Singleton
internal class ToolRegistry(tools: List<Tool>, directory: AgentDirectory) : Lifecycle {
    private val tools: Map<String, Tool>
    private val selected: Map<AgentId, List<Tool>>

    init {
        val named = sortedMapOf<String, Tool>()
        for (tool in tools) {
            val name = tool.definition.name
            val other = named.putIfAbsent(name, tool)
            if (other != null) {
                throw ConfigException(
                    null,
                    "Tool '$name' is contributed by both ${other.javaClass.name} and ${tool.javaClass.name}: switch " +
                        "off the plugin of one of them.",
                )
            }
        }
        this.tools = named
        selected = directory.agents.associate { agent ->
            val allow = agent.config.tools.allow.map(ToolGlob::of)
            val deny = agent.config.tools.deny.map(ToolGlob::of)
            agent.id to named.values.filter { tool ->
                val name = tool.definition.name
                allow.any { it.matches(name) } && deny.none { it.matches(name) }
            }
        }
    }

    fun tool(name: String): Tool? = tools[name]

    /** The tools [agent] selects that may be offered before the permission layer exists, sorted by name. */
    fun offered(agent: AgentId): List<Tool> = selected[agent].orEmpty().filter { it.definition.risk == OFFERED_RISK }

    override suspend fun onStart() {
        for ((agent, tools) in selected) {
            val withheld = tools.filter { it.definition.risk != OFFERED_RISK }
            if (withheld.isEmpty()) continue
            logger.info(
                "Agent '{}' is not offered {}: only {} tools are offered until the permission layer exists",
                agent,
                withheld.joinToString { "${it.definition.name} (${it.definition.risk})" },
                OFFERED_RISK,
            )
        }
    }

    private companion object {
        val OFFERED_RISK = ToolRisk.READ_ONLY
    }
}

private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)
