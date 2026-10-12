package org.foedusprogramme.alexandrite.channel.onebot.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.channel.onebot.channel.OneBotApis
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk

/**
 * Reads what the platform knows about a chat, a member or a message this bot acts in.
 *
 * One tool serves every action it may be asked for rather than one tool per action, because an action is a row of
 * [OneBotActions] and not a class: the platform's surface is large, and a row says what an action takes and what it
 * is worth. What the call may ask for is the table, so an action the table leaves out cannot be named at all, which
 * is where the actions that hand out the account's credentials and the ones that restart the implementation are kept.
 */
@Singleton
@Contribute(Tool::class)
internal class OneBotReadTool(private val apis: OneBotApis) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "onebot.read",
        description = describe("Reads what the QQ platform knows", OneBotActions.rows.filter { !it.changes }),
        parameters = schema(),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        call(apis, OneBotActions.rows.filter { !it.changes }, arguments, context)
}

/** Changes something of a chat or a member of the platform this bot acts in. */
@Singleton
@Contribute(Tool::class)
internal class OneBotActTool(private val apis: OneBotApis) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "onebot.act",
        description = describe(
            "Changes something of a QQ chat this bot acts in",
            OneBotActions.rows.filter {
                it.changes
            },
        ),
        parameters = schema(),
        risk = ToolRisk.EXTERNAL_ACTION,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        call(apis, OneBotActions.rows.filter { it.changes }, arguments, context)
}

/** The arguments every call of these tools takes, which is the action and whatever that action takes. */
private fun schema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("action") {
            put("type", "string")
            put("description", "The action to call, named as one of the actions listed in the description.")
        }
        putJsonObject("params") {
            put("type", "object")
            put("description", "The arguments of that action, by the names the description gives them.")
            put("additionalProperties", true)
        }
    }
    put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("action"))))
    put("additionalProperties", false)
}

/** What the model is told these tools are for: every action they may be asked for, and what it takes. */
private fun describe(header: String, rows: List<OneBotActions.Row>): String = buildString {
    append(header)
    append(". It calls one action per call, named as 'action', with that action's arguments under 'params'. ")
    append("The actions it may be asked for:\n")
    rows.forEach { row ->
        append("- ").append(row.name).append('(')
        append(row.parameters.joinToString(", ") { it.name + (if (it.required) "" else "?") })
        append(") \u2014 ").append(row.said).append('\n')
        row.parameters.forEach { parameter ->
            append("    ").append(parameter.name).append(": ").append(parameter.description).append('\n')
        }
    }
}

/**
 * Calls [action] of [rows] in the chat of [context], and says what the platform answered.
 *
 * Only the arguments the row declares are sent, so a call cannot put anything of its own into the request. An
 * argument that was left out falls back to the message of this turn where the row allows it, and is refused
 * otherwise, because a prompt does not name that id.
 */
private suspend fun call(
    apis: OneBotApis,
    rows: List<OneBotActions.Row>,
    arguments: JsonObject,
    context: ToolContext,
): ToolResult {
    val name = arguments.text("action")
        ?: return ToolResult("A call names an action, as action.", isError = true)
    val row = rows.firstOrNull { it.name == name }
        ?: return ToolResult(
            "'$name' is no action this call may ask for. It may ask for: " +
                rows.joinToString(", ") { it.name } + ".",
            isError = true,
        )
    val given = arguments["params"] as? JsonObject ?: JsonObject(emptyMap())
    val params = buildJsonObject {
        row.parameters.forEach { parameter ->
            val value = given[parameter.name]
            if (value == null || value is JsonNull) {
                val message = parameter.name == OneBotActions.MESSAGE_ID && parameter.optional
                val fallback = if (message) context.trigger?.id else null
                when {
                    fallback != null -> putId(parameter.name, fallback)

                    parameter.required -> return ToolResult(
                        "The action '$name' needs ${parameter.name}: ${parameter.description}",
                        isError = true,
                    )
                }
            } else {
                put(parameter.name, value)
            }
        }
    }
    val api = apis.serving(context.turn.chat)
    return ToolResult(api.raw(name, params).outcome(row.said))
}
