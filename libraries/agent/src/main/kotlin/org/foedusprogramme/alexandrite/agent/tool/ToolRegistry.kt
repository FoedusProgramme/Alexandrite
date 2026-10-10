package org.foedusprogramme.alexandrite.agent.tool

import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** The contributed tools by name, and the ones each agent selects. */
@Singleton
internal class ToolRegistry(tools: List<Tool>, directory: AgentDirectory) : Lifecycle {
    private val tools: Map<String, Tool>
    private val selected: Map<AgentId, List<Tool>>
    private val forMembers: Map<AgentId, List<ToolGlob>> =
        directory.agents.associate { agent -> agent.id to agent.config.tools.forMembers.map(ToolGlob::of) }

    /** The models already warned of for taking no tools. */
    private val toolless = ConcurrentHashMap.newKeySet<ModelRef>()

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

    /** The tools a turn of [agent] offers [model]: all to an [admin], those of `tools.forMembers` to anyone else. */
    fun offered(agent: AgentId, admin: Boolean, model: ModelRef, nativeTools: Boolean): List<Tool> {
        val members = forMembers[agent].orEmpty()
        val tools = offered(agent).filter { tool -> admin || members.any { it.matches(tool.definition.name) } }
        if (nativeTools || tools.isEmpty()) return tools
        if (toolless.add(model)) logger.warn("Model {} takes no tools, so its turns are offered none", model)
        return emptyList()
    }

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
