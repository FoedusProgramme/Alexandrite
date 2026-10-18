package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.plugin.PluginOrigins
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutputPart
import java.util.concurrent.ConcurrentHashMap

/** A tool of the agent plugin that only some agents can use. */
internal interface AgentScopedTool : Tool {
    /** Whether [agent] can use the tool, asked once at the start. */
    fun usableBy(agent: AgentId): Boolean
}

/** A tool of the agent plugin that an agent is offered only beside other tools. */
internal interface CompanionTool : Tool

/** A tool of the agent plugin whose definition is rendered anew for each turn that offers it. */
internal interface RenderedTool : Tool {
    suspend fun definitionFor(turn: TurnInfo): ToolDefinition
}

/** A tool of the agent plugin whose calls run in their turn until they end and report their own outcome. */
internal interface InTurnTool : Tool {
    suspend fun call(arguments: JsonObject, context: AgentToolContext): InTurnResult
}

/** What a call of an [InTurnTool] gives the model, escaped by the tool. */
internal class InTurnResult(val content: List<ToolOutputPart>, val outcome: ToolOutcome) {
    constructor(text: String, outcome: ToolOutcome) : this(listOf(TextPart(text)), outcome)
}

/**
 * The contributed tools by name, the ones that [sources] offer now, and the ones each agent selects among both: a
 * source's tool counts as built-in, and as its plugin's, where its source does.
 */
@Singleton
internal class ToolRegistry(
    tools: List<Tool>,
    private val sources: List<ToolSource>,
    directory: AgentDirectory,
    private val origins: PluginOrigins,
) {
    private val tools: Map<String, Tool>

    /** The contributed tools each agent selects, before the companion rule. */
    private val chosen: Map<AgentId, List<Tool>>
    private val globs: Map<AgentId, Globs>
    private val builtIn: Set<String> = tools.filter(origins::isBuiltIn).mapTo(HashSet()) { it.definition.name }
    private val forGuests: Map<AgentId, List<ToolGlob>> =
        directory.agents.associate { agent -> agent.id to agent.config.tools.forGuests.map(ToolGlob::of) }

    /** The models already warned of for taking no tools. */
    private val toolless = ConcurrentHashMap.newKeySet<ModelRef>()

    /** The source tools already warned of, by why they are not offered. */
    private val dropped = ConcurrentHashMap.newKeySet<String>()

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
        globs = directory.agents.associate { agent ->
            agent.id to Globs(agent.config.tools.allow.map(ToolGlob::of), agent.config.tools.deny.map(ToolGlob::of))
        }
        chosen = directory.agents.associate { agent ->
            val globs = globs.getValue(agent.id)
            agent.id to named.values.filter { tool ->
                globs.select(tool.definition.name) && (tool as? AgentScopedTool)?.usableBy(agent.id) != false
            }
        }
    }

    /** The tools that the sources offer now, each with its source, by name. */
    private fun sourced(): Map<String, Sourced> {
        val found = sortedMapOf<String, Sourced>()
        for (source in sources) {
            for (tool in source.tools.value) {
                val name = tool.definition.name
                val why = when {
                    !name.startsWith(
                        source.namespace + ".",
                    ) -> "it lies outside the source's namespace ${source.namespace}"

                    name in tools -> "a contributed tool has its name"

                    name in found -> "another source offers a tool of its name"

                    else -> null
                }
                if (why == null) {
                    found[name] = Sourced(tool, source)
                } else if (dropped.add("$name: $why")) {
                    logger.warn("Tool '{}' of {} is not offered: {}", name, source.javaClass.name, why)
                }
            }
        }
        return found
    }

    /** Whether [name] lies in the namespace of a source, whose tools come and go. */
    fun isSourced(name: String): Boolean = sources.any { name.startsWith(it.namespace + ".") }

    fun tool(name: String): Tool? = tools[name] ?: sourced()[name]?.tool

    /** Whether a built-in plugin contributed [tool]. */
    fun isBuiltIn(tool: Tool): Boolean {
        val name = tool.definition.name
        if (tools[name] === tool) return name in builtIn
        return sourceOf(tool)?.let(origins::isBuiltIn) == true
    }

    /** The id of the plugin that contributed [tool], null when none did. */
    fun pluginOf(tool: Tool): String? = origins.pluginOf(tool) ?: sourceOf(tool)?.let(origins::pluginOf)

    /** The tools [agent] selects, sorted by name. */
    fun offered(agent: AgentId): List<Tool> {
        val globs = globs[agent] ?: return emptyList()
        val fromSources = sourced().values.map { it.tool }.filter { globs.select(it.definition.name) }
        val all = (chosen[agent].orEmpty() + fromSources).sortedBy { it.definition.name }
        return all.takeIf { tools -> tools.any { it !is CompanionTool } }.orEmpty()
    }

    /** The source that offers [tool] now, null for a contributed tool or one no source offers. */
    private fun sourceOf(tool: Tool): ToolSource? = sourced()[tool.definition.name]?.takeIf { it.tool === tool }?.source

    /** The tools that every turn of [agent] offers [model]. */
    fun offered(agent: AgentId, model: ModelRef, nativeTools: Boolean): List<Tool> {
        val tools = offered(agent)
        if (nativeTools || tools.isEmpty()) return tools
        if (toolless.add(model)) logger.warn("Model {} takes no tools, so its turns are offered none", model)
        return emptyList()
    }

    /** Whether a turn of [agent] may call [tool]: an [operator]'s any, anyone else's those of `tools.forGuests`. */
    fun permits(agent: AgentId, operator: Boolean, tool: String): Boolean =
        operator || forGuests[agent].orEmpty().any { it.matches(tool) }
}

/** An agent's `tools.allow` and `tools.deny`. */
private class Globs(val allow: List<ToolGlob>, val deny: List<ToolGlob>) {
    fun select(name: String): Boolean = allow.any { it.matches(name) } && deny.none { it.matches(name) }
}

/** A [tool] that [source] offers. */
private class Sourced(val tool: Tool, val source: ToolSource)

private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)
