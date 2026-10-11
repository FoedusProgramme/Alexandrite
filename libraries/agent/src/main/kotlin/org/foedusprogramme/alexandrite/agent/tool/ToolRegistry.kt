package org.foedusprogramme.alexandrite.agent.tool

import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginOrigins
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** A tool of the agent plugin that only some agents can use. */
internal interface AgentScopedTool : Tool {
    /** Whether [agent] can use the tool, asked once at the start. */
    fun usableBy(agent: AgentId): Boolean
}

/** A tool of the agent plugin that an agent is offered only beside other tools. */
internal interface CompanionTool : Tool

/** The contributed tools by name, and the ones each agent selects. */
@Singleton
internal class ToolRegistry(tools: List<Tool>, directory: AgentDirectory, origins: PluginOrigins) {
    private val tools: Map<String, Tool>
    private val selected: Map<AgentId, List<Tool>>
    private val builtIn: Set<String> = tools.filter(origins::isBuiltIn).mapTo(HashSet()) { it.definition.name }
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
            val chosen = named.values.filter { tool ->
                val name = tool.definition.name
                allow.any { it.matches(name) } && deny.none { it.matches(name) } &&
                    (tool as? AgentScopedTool)?.usableBy(agent.id) != false
            }
            agent.id to chosen.takeIf { tools -> tools.any { it !is CompanionTool } }.orEmpty()
        }
    }

    fun tool(name: String): Tool? = tools[name]

    /** Whether a built-in plugin contributed [tool]. */
    fun isBuiltIn(tool: Tool): Boolean {
        val name = tool.definition.name
        return tools[name] === tool && name in builtIn
    }

    /** The tools [agent] selects, sorted by name. */
    fun offered(agent: AgentId): List<Tool> = selected[agent].orEmpty()

    /** The tools that every turn of [agent] offers [model]. */
    fun offered(agent: AgentId, model: ModelRef, nativeTools: Boolean): List<Tool> {
        val tools = offered(agent)
        if (nativeTools || tools.isEmpty()) return tools
        if (toolless.add(model)) logger.warn("Model {} takes no tools, so its turns are offered none", model)
        return emptyList()
    }

    /** Whether a turn of [agent] may call [tool]: an [admin]'s any, anyone else's those of `tools.forMembers`. */
    fun permits(agent: AgentId, admin: Boolean, tool: String): Boolean =
        admin || forMembers[agent].orEmpty().any { it.matches(tool) }
}

private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)
