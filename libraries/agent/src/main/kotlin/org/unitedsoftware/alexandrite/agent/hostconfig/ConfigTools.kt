package org.unitedsoftware.alexandrite.agent.hostconfig

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.tool.AgentScopedTool
import org.unitedsoftware.alexandrite.agent.tool.AgentToolContext
import org.unitedsoftware.alexandrite.agent.tool.CompanionTool
import org.unitedsoftware.alexandrite.agent.tool.property
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

@Singleton
@Contribute(Tool::class)
internal class ConfigGetTool(private val config: HostConfig, private val paths: HostPaths) :
    CompanionTool,
    AgentScopedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "config.get",
        description = "Shows a value of Alexandrite's config file, by its path of dot-separated keys such as " +
            "agent.agents.main.models, or the whole file without a path. Secrets show as \${env:NAME} references or " +
            "as ${HostConfig.HIDDEN_SECRET}, never as their values.",
        parameters = schema {
            property("path", "string", "The dot-separated keys of the value, such as agent.floor. Default the file.")
        },
        risk = ToolRisk.READ_ONLY,
        activity = ToolActivity.READ,
        titleArguments = listOf("path"),
    )

    override fun usableBy(agent: AgentId): Boolean = paths.configFile != null

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = answering {
        val path = optionalPath(arguments)
        val value = config.get(path.orEmpty())
            ?: return@answering ToolResult("Nothing is set at $path: the default applies.")
        ToolResult("${path ?: "The config file"} = ${PRETTY.encodeToString(JsonElement.serializer(), value)}")
    }
}

@Singleton
@Contribute(Tool::class)
internal class ConfigSetTool(private val config: HostConfig, private val paths: HostPaths) :
    ReviewedTool,
    CompanionTool,
    AgentScopedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "config.set",
        description = "Changes a value of Alexandrite's config file, by its path of dot-separated keys, as an " +
            "operator's `alexandrite config set` does. The whole file must still pass the checks of a start, or " +
            "nothing changes. A secret is written as an \${env:NAME} reference, never as its value. Says which " +
            "changes took effect at once and which need a restart, which an operator does.",
        parameters = schema("path", "value") {
            property("path", "string", "The dot-separated keys of the value, such as agent.agents.main.models.")
            putJsonObject("value") {
                put("description", "The new value as JSON: a string, number, boolean, list or object.")
            }
        },
        risk = ToolRisk.EXTERNAL_ACTION,
        activity = ToolActivity.EDIT,
        titleArguments = listOf("path"),
    )

    override fun usableBy(agent: AgentId): Boolean = paths.configFile != null

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = reviewing {
        val path = requiredPath(arguments)
        val value = arguments["value"] ?: throw Refused("Give 'value', the new value as JSON.")
        reviewed(config, path, value, tainted(context)) { set(path, value) }
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = answering {
        val path = requiredPath(arguments)
        val value = arguments["value"] ?: throw Refused("Give 'value', the new value as JSON.")
        changed(config, path, value, context) { set(path, value) }
    }
}

@Singleton
@Contribute(Tool::class)
internal class ConfigUnsetTool(private val config: HostConfig, private val paths: HostPaths) :
    ReviewedTool,
    CompanionTool,
    AgentScopedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "config.unset",
        description = "Takes a value out of Alexandrite's config file, by its path of dot-separated keys, so that " +
            "its default applies, as an operator's `alexandrite config unset` does. The whole file must still pass " +
            "the checks of a start, or nothing changes. Says whether the change took effect at once or needs a " +
            "restart, which an operator does.",
        parameters = schema("path") {
            property("path", "string", "The dot-separated keys of the value, such as agent.agents.main.workspace.")
        },
        risk = ToolRisk.EXTERNAL_ACTION,
        activity = ToolActivity.EDIT,
        titleArguments = listOf("path"),
    )

    override fun usableBy(agent: AgentId): Boolean = paths.configFile != null

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = reviewing {
        val path = requiredPath(arguments)
        reviewed(config, path, null, tainted(context)) { remove(path) }
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = answering {
        val path = requiredPath(arguments)
        changed(config, path, null, context) { remove(path) }
    }
}

/** Ends a config tool's call with [message] for the model. */
private class Refused(override val message: String) : Exception(message)

/** The review of a change of [path] to [value] (null to remove it) that [change] makes in a [tainted] conversation. */
private suspend fun reviewed(
    config: HostConfig,
    path: String,
    value: JsonElement?,
    tainted: Boolean,
    change: ConfigEdit.() -> Unit,
): CallReview {
    val current = config.get(path)
    val changes = config.check(change)
    if (changes != null && changes.changed.isEmpty()) {
        throw Refused("The config file holds this already at $path: nothing would change.")
    }
    val what = if (value == null) "Remove $path" else "Set $path to:\n${pretty(value)}"
    if (what.length > MAX_SHOWN_CHARS) {
        throw Refused(
            "The value is too long for a person to review whole: change its parts one by one, each by its own path.",
        )
    }
    val effect = changes?.let { "\n${effect(it)}" }.orEmpty()
    return CallReview.builder()
        .preview(ReviewPreview.Text("$what$effect"))
        .snapshot(snapshot(current))
        .person(person(config, path, value, tainted))
        .build()
}

/** Why only a person may change [path] to [value] (null to remove it) in a [tainted] conversation, null if anyone may. */
private suspend fun person(config: HostConfig, path: String, value: JsonElement?, tainted: Boolean): String? {
    if (touchesFloor(config, path, value)) return FLOOR_CHANGE
    if (!tainted) return null
    val leaves = mutableListOf<List<String>>()
    changedLeaves(path.split('.'), config.get(path), value, leaves)
    val guarded = leaves.firstOrNull(::guards) ?: return null
    return "the change reaches ${guarded.joinToString(".")}, a guardrail, which only a person may change in a " +
        "conversation that took in untrusted content"
}

/** Whether a call of [context] counts as made in a tainted conversation, as one whose mark it cannot tell does. */
private fun tainted(context: ToolContext): Boolean = (context as? AgentToolContext)?.taint?.tainted != false

/** Adds to [into] the keys below [keys] whose values differ from [old] to [new], each object followed down. */
private fun changedLeaves(keys: List<String>, old: JsonElement?, new: JsonElement?, into: MutableList<List<String>>) {
    if (old == new) return
    val before = old as? JsonObject
    val after = new as? JsonObject
    if (before == null && after == null) {
        into += keys
        return
    }
    if ((before == null && old != null && old != JsonNull) || (after == null && new != null && new != JsonNull)) {
        into += keys
    }
    for (key in before?.keys.orEmpty() + after?.keys.orEmpty()) {
        changedLeaves(keys + key, before?.get(key), after?.get(key), into)
    }
}

/**
 * Whether the config key [keys] holds a guardrail: who operates, what calls run or are trusted unasked, the judge, the
 * sandbox, what steers an agent, and which plugins run.
 */
private fun guards(keys: List<String>): Boolean {
    val root = keys.getOrNull(0)
    val section = keys.getOrNull(1)
    return when {
        keys.lastOrNull() == "enabled" -> true
        root == "agent" && section in AGENT_GUARDS -> true
        root == "agent" && section == "agents" && keys.getOrNull(3) in AGENTS_GUARDS -> true
        root == "mcp" && section == "servers" -> true
        root == "tools" && (section to keys.getOrNull(2)) in TOOLS_GUARDS -> true
        root == "channels" && keys.getOrNull(2) == "instances" && keys.getOrNull(4) in CHANNEL_GUARDS -> true
        else -> false
    }
}

/**
 * What [change] of [path] to [value] (null to remove it) wrote, refused when the value changed since the call's review
 * or nobody allowed a change of the floor.
 */
private suspend fun changed(
    config: HostConfig,
    path: String,
    value: JsonElement?,
    context: ToolContext,
    change: ConfigEdit.() -> Unit,
): ToolResult {
    val clearance = context.clearance
    val reviewed = clearance?.snapshot ?: throw Refused("This call was not reviewed, so it changes nothing.")
    if (reviewed != snapshot(config.get(path))) {
        throw Refused("$path changed since this call was reviewed: repeat the call if it still applies.")
    }
    if (clearance.kind != ClearanceKind.HUMAN) {
        person(config, path, value, tainted(context))?.let {
            throw Refused("${it.replaceFirstChar(Char::uppercaseChar)}, and no person allowed it.")
        }
    }
    val changes = config.edit(change)
    if (changes.changed.isEmpty()) return ToolResult("Nothing changed: the config file holds this already.")
    logger.info(
        "Turn {} changed {} in the config file, cleared by {}",
        context.turn.id,
        changes.changed.joinToString(),
        clearance.kind,
    )
    return ToolResult("Changed ${changes.changed.joinToString()}. ${effect(changes)}")
}

/** Whether removing [path], or setting it to [value], changes `agent.floor`. */
private suspend fun touchesFloor(config: HostConfig, path: String, value: JsonElement?): Boolean {
    val keys = path.split('.')
    if (keys.size >= FLOOR.size && keys.take(FLOOR.size) == FLOOR) return true
    if (FLOOR.take(keys.size) != keys) return false
    val written = FLOOR.drop(keys.size).fold(value) { element, key -> (element as? JsonObject)?.get(key) }
    return written != config.get(FLOOR.joinToString("."))
}

/** Which of [changes] took effect at once and which need a restart. */
private fun effect(changes: ConfigChanges): String {
    val live = changes.changed - changes.restart.toSet()
    return listOfNotNull(
        live.takeIf { it.isNotEmpty() }?.let { "Takes effect at once: ${it.joinToString()}." },
        changes.restart.takeIf { it.isNotEmpty() }?.let {
            "Takes effect once an operator restarts Alexandrite: ${it.joinToString()}."
        },
    ).joinToString(" ")
}

private fun pretty(value: JsonElement): String = PRETTY.encodeToString(JsonElement.serializer(), value)

/** What the call compares before it changes the file: the value at its path as its review found it. */
private fun snapshot(value: JsonElement?): String = "value:${value ?: JsonNull}"

private fun optionalPath(arguments: JsonObject): String? {
    val value = arguments["path"]
    if (value == null || value == JsonNull) return null
    val path = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Refused("'path' must be a string.")
    return path.takeIf { it.isNotEmpty() }?.let(::checkedPath)
}

private fun requiredPath(arguments: JsonObject): String =
    optionalPath(arguments) ?: throw Refused("Give 'path', the dot-separated keys of the value.")

private fun checkedPath(path: String): String {
    if (path.split('.').any(String::isEmpty)) throw Refused("'$path' is no path of dot-separated keys.")
    return path
}

/** The review that [block] makes, a problem for what it refuses. */
private suspend fun reviewing(block: suspend () -> CallReview): CallReview = try {
    block()
} catch (e: Refused) {
    CallReview.builder().problem(e.message).build()
} catch (e: ConfigEditException) {
    CallReview.builder().problem(e.message ?: "The config file refuses this change.").build()
}

/** What [block] returns, or the failed result of what it refuses. */
private suspend fun answering(block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: Refused) {
    ToolResult(e.message, isError = true)
} catch (e: ConfigEditException) {
    ToolResult(e.message ?: "The config file refuses this change.", isError = true)
}

private fun schema(vararg required: String, properties: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties", properties)
    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    put("additionalProperties", false)
}

private val FLOOR = listOf("agent", "floor")

private const val FLOOR_CHANGE =
    "the change reaches agent.floor, the places of the hard floor, which only a person may change"

/** The characters of a change that a person reviews at most. */
private const val MAX_SHOWN_CHARS = 12_000

private val AGENT_GUARDS = setOf("floor", "trustedFolders")

private val AGENTS_GUARDS = setOf("permission", "sandbox", "tools", "instructions", "instructionFiles", "channels")

private val TOOLS_GUARDS = setOf("exec" to "environment", "web" to "allowedPrivateHosts")

private val CHANNEL_GUARDS =
    setOf("adminUserIds", "trustedUserIds", "allowedChats", "commands", "apiBaseUrl", "botToken")

private val PRETTY = Json { prettyPrint = true }

private val logger = LoggerFactory.getLogger(ConfigSetTool::class.java)
