package org.foedusprogramme.alexandrite.channel.onebot.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.channel.onebot.channel.OneBotApis
import org.foedusprogramme.alexandrite.channel.onebot.protocol.result.OneBotResult
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
 * is worth. What a call may ask for is the table, so an action the table leaves out cannot be named at all, which is
 * where the actions that hand out the account's credentials and the ones that restart the implementation are kept.
 */
@Singleton
@Contribute(Tool::class)
internal class OneBotReadTool(private val apis: OneBotApis) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "onebot.read",
        description = describe("Reads what the QQ platform knows", OneBotActions.rows.filterNot { it.changes }),
        parameters = schema(),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        call(apis, OneBotActions.rows.filterNot { it.changes }, arguments, context)
}

/** Changes something of a chat or a member of the platform this bot acts in. */
@Singleton
@Contribute(Tool::class)
internal class OneBotActTool(private val apis: OneBotApis) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "onebot.act",
        description = describe(
            "Changes something of a QQ chat this bot acts in",
            OneBotActions.rows.filter { it.changes },
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
            put("description", "The action to call, named as one of the actions the description lists.")
        }
        putJsonObject("params") {
            put("type", "object")
            put("description", "That action's arguments, by the names the description gives them.")
            put("additionalProperties", true)
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("action"))))
    put("additionalProperties", false)
}

/** What the model is told these tools are for: every action they may be asked for, and what it takes. */
private fun describe(header: String, rows: List<OneBotActions.Row>): String = buildString {
    append(header)
    append(". It calls one action per call, named as 'action', with that action's arguments under 'params'. ")
    append("The actions it may be asked for, with a ? on the arguments that may be left out:\n")
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
 * Calls [name] of [rows] in the chat of [context], and says what the platform answered.
 *
 * An action the call may not ask for is refused rather than passed on, and only the arguments its row declares are
 * sent, so nothing a call writes under `params` can reach the platform unless the action takes it by that name.
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
    val params = when (val prepared = prepare(row, given, context.trigger?.id)) {
        is Prepared.Refused -> return ToolResult(prepared.said, isError = true)
        is Prepared.Ready -> prepared.params
    }
    val api = apis.serving(context.turn.chat)
    val result = api.raw(name, params)
    // What a read answers is what other people wrote, so a turn that carries it carries content this bot cannot vouch
    // for, and the conversation is marked the way any other content read from outside is marked.
    if (!row.changes && result is OneBotResult.Ok) {
        context.untrusted("the platform answered what '$name' asked for")
    }
    return ToolResult(result.outcome(row.said, answered = !row.changes))
}

/** What a call to an action amounts to: the arguments to send, or why they cannot be sent. */
internal sealed interface Prepared {
    /** The arguments to send, which are the ones [OneBotActions.Row.parameters] declares. */
    class Ready(val params: JsonObject) : Prepared

    /** Why nothing is sent. */
    class Refused(val said: String) : Prepared
}

/**
 * The arguments [row] sends when a call gave [given], with [messageId] standing in for the id of the message of the
 * turn where the row allows it to be left out.
 *
 * Every argument is written the way the standard asks for it, since a model writes them all as text: one that names a
 * message, a group or a length is sent as the number it names, and one that asks whether to do something is sent as
 * yes or no. An argument that must be given and was not, or one that cannot be read as what the row says it is, is
 * refused, naming it and what it is for, rather than sent as something the platform would answer without saying what
 * the call got wrong.
 */
internal fun prepare(row: OneBotActions.Row, given: JsonObject, messageId: String?): Prepared {
    val params = buildJsonObject {
        row.parameters.forEach { parameter ->
            val value = given[parameter.name]
            if (value != null && value !is JsonNull) {
                when (parameter.kind) {
                    OneBotActions.Kind.TEXT -> put(parameter.name, value)

                    OneBotActions.Kind.NUMBER -> put(parameter.name, number(value))

                    OneBotActions.Kind.FLAG -> {
                        val flag = boolean(value)
                            ?: return Prepared.Refused(
                                "The action '${row.name}' takes ${parameter.name} as yes or no, not '$value': " +
                                    parameter.description,
                            )
                        put(parameter.name, flag)
                    }
                }
                return@forEach
            }
            // An action about a message means the message of this turn when the call named none. A turn no message
            // started leaves nothing to mean, and a call that had to name one is refused rather than sent without it.
            if (parameter.name == OneBotActions.MESSAGE_ID && parameter.optional) {
                val fallback = messageId ?: return Prepared.Refused(
                    "The action '${row.name}' needs ${parameter.name}: ${parameter.description}",
                )
                put(parameter.name, number(JsonPrimitive(fallback)))
                return@forEach
            }
            if (parameter.required) {
                return Prepared.Refused(
                    "The action '${row.name}' needs ${parameter.name}: ${parameter.description}",
                )
            }
        }
    }
    return Prepared.Ready(params)
}

/**
 * [value] as yes or no, null when it says neither.
 *
 * A model writes an argument as text even when it asks whether to do something, so both the words it may write and
 * the values a call may carry are read, and anything else is refused rather than sent as something the platform
 * would answer without saying what the call got wrong.
 */
private fun boolean(value: JsonElement): Boolean? {
    val primitive = value as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "yes", "y", "1" -> true
        "no", "n", "0" -> false
        else -> null
    }
}

/** [value] as the number it names, or as the text it is when it names no number. */
private fun number(value: JsonElement): JsonElement {
    val text = (value as? JsonPrimitive)?.contentOrNull ?: return value
    return text.toLongOrNull()?.let { JsonPrimitive(it) } ?: value
}
