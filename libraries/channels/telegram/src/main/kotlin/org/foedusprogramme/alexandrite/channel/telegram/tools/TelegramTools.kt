package org.foedusprogramme.alexandrite.channel.telegram.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.channel.telegram.reply.Target
import org.foedusprogramme.alexandrite.channel.telegram.tools.ChatDesk.Companion.QUOTED
import org.foedusprogramme.alexandrite.channel.telegram.tools.ChatDesk.Companion.THIS
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.CallReview
import org.foedusprogramme.alexandrite.sdk.tool.ReviewPreview
import org.foedusprogramme.alexandrite.sdk.tool.ReviewedTool
import org.foedusprogramme.alexandrite.sdk.tool.RuleSubject
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk

/**
 * A tool of the Telegram plugin, which acts in the chat of the turn that calls it, or in another paired chat of the
 * same bot, and shows the person who approves it what it does.
 */
internal abstract class TelegramTool(private val desks: TelegramDesks) : ReviewedTool {
    /** What the call of [arguments] in [turn] does at [desk]. */
    protected abstract suspend fun plan(arguments: JsonObject, turn: TurnInfo, desk: ChatDesk): Planned

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = try {
        val planned = planned(arguments, context.turn)
        CallReview(preview = ReviewPreview.Text(planned.shown), rule = planned.rule)
    } catch (e: ToolProblem) {
        CallReview(problem = e.message)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = try {
        ToolResult(planned(arguments, context.turn).run())
    } catch (e: ToolProblem) {
        ToolResult(e.message.orEmpty(), isError = true)
    }

    private suspend fun planned(arguments: JsonObject, turn: TurnInfo): Planned {
        val desk = desks.of(turn.chat.instance)
            ?: throw ToolProblem("${definition.name} acts only in the chats of a Telegram bot that runs.")
        return plan(arguments, turn, desk)
    }
}

/** A call as it is [shown] for approval, allowed for good by [rule] unless it is null, and [run] once cleared. */
internal class Planned(val shown: String, val rule: RuleSubject? = null, val run: suspend () -> String)

@Singleton
@Contribute(Tool::class)
internal class ChatsTool(desks: TelegramDesks) : TelegramTool(desks) {
    override val definition = ToolDefinition(
        name = "telegram.chats",
        description = "Lists the chats paired with the Telegram bot of this chat, the ids that telegram.send takes.",
        parameters = schema(),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun plan(arguments: JsonObject, turn: TurnInfo, desk: ChatDesk): Planned = Planned(
        "List the paired chats",
    ) {
        val lines = desk.paired().map { paired ->
            val chat = paired.chat
            val kind = if (chat.chat.startsWith("-")) "group" else "direct chat"
            val here = if (chat.chat == turn.chat.chat) ", this chat" else ""
            "- ${chat.chat}: ${desk.name(chat)} ($kind$here)"
        }
        if (lines.isEmpty()) "No chat is paired with this bot." else "The paired chats:\n" + lines.joinToString("\n")
    }
}

@Singleton
@Contribute(Tool::class)
internal class SendTool(desks: TelegramDesks) : TelegramTool(desks) {
    override val definition = ToolDefinition(
        name = "telegram.send",
        description = "Sends a Markdown message from the bot to another chat paired with it, by the chat's id " +
            "(see telegram.chats). Your reply already reaches this chat: use it only to reach another one.",
        parameters = schema(
            listOf("chat", "text"),
            "chat" to property("string", "The id of the chat, such as -1001234567890."),
            "text" to property("string", "The message, in Markdown."),
        ),
        risk = ToolRisk.EXTERNAL_ACTION,
        titleArguments = listOf("chat"),
    )

    override suspend fun plan(arguments: JsonObject, turn: TurnInfo, desk: ChatDesk): Planned {
        val chat = ChatAddress(desk.instance, arguments.required("chat"))
        val text = arguments.required("text")
        desk.target(chat)
        val name = desk.name(chat)
        return Planned("Send to $name:\n\n$text") {
            when (val delivery = desk.send(chat, text)) {
                is Delivery.Delivered -> "Sent to $name as message ${delivery.messages.first().id}."

                is Delivery.NotDelivered -> {
                    val why = listOfNotNull("${delivery.kind}", delivery.detail).joinToString(": ")
                    throw ToolProblem("The message did not reach $name: $why.")
                }
            }
        }
    }
}

@Singleton
@Contribute(Tool::class)
internal class ReactTool(desks: TelegramDesks) : TelegramTool(desks) {
    override val definition = ToolDefinition(
        name = "telegram.react",
        description = "Reacts with an emoji to a message of this chat, by default the message you answer. " +
            "Telegram takes only its own reaction emoji, such as 👍 ❤ 🔥 🎉 🤔 👀.",
        parameters = schema(
            listOf("emoji"),
            "emoji" to property("string", "The reaction, one emoji."),
            "message" to property("string", MESSAGE + " Default \"$THIS\"."),
        ),
        risk = ToolRisk.EXTERNAL_ACTION,
        titleArguments = listOf("emoji"),
    )

    override suspend fun plan(arguments: JsonObject, turn: TurnInfo, desk: ChatDesk): Planned {
        val target = desk.target(turn.chat)
        val emoji = arguments.required("emoji")
        val message = desk.message(turn, arguments.text("message"), THIS)
        val shown = "React with $emoji to message $message in ${desk.name(turn.chat)}"
        return Planned(shown, RuleSubject(definition.name, emptyList(), "any reaction with telegram.react")) {
            val reaction = buildJsonObject {
                put("type", "emoji")
                put("emoji", emoji)
            }
            val params = onMessage(target, message) { put("reaction", JsonArray(listOf(reaction))) }
            desk.act(target, "setMessageReaction", params)
            "Reacted with $emoji to message $message."
        }
    }
}

@Singleton
@Contribute(Tool::class)
internal class PinTool(desks: TelegramDesks) : TelegramTool(desks) {
    override val definition = ToolDefinition(
        name = "telegram.pin",
        description = "Pins a message of this chat, by default the one that the message you answer replies to. " +
            "The bot must be allowed to pin messages there.",
        parameters = schema(
            emptyList(),
            "message" to property("string", MESSAGE + " Default \"$QUOTED\"."),
            "notify" to property("boolean", "Whether the chat's members are notified (default false)."),
        ),
        risk = ToolRisk.EXTERNAL_ACTION,
    )

    override suspend fun plan(arguments: JsonObject, turn: TurnInfo, desk: ChatDesk): Planned {
        val target = desk.target(turn.chat)
        val message = desk.message(turn, arguments.text("message"), QUOTED)
        val notify = arguments.flag("notify") ?: false
        val how = if (notify) "notifying its members" else "quietly"
        return Planned("Pin message $message in ${desk.name(turn.chat)}, $how") {
            desk.act(target, "pinChatMessage", onMessage(target, message) { put("disable_notification", !notify) })
            "Pinned message $message."
        }
    }
}

@Singleton
@Contribute(Tool::class)
internal class TitleTool(desks: TelegramDesks) : TelegramTool(desks) {
    override val definition = ToolDefinition(
        name = "telegram.set_title",
        description = "Renames this group. The bot must be an admin allowed to change the group's info.",
        parameters = schema(listOf("title"), "title" to property("string", "The new title, 1 to 128 characters.")),
        risk = ToolRisk.EXTERNAL_ACTION,
        titleArguments = listOf("title"),
    )

    override suspend fun plan(arguments: JsonObject, turn: TurnInfo, desk: ChatDesk): Planned {
        val target = desk.target(turn.chat)
        if (!target.group) throw ToolProblem("Only a group has a title that the bot may change.")
        val title = arguments.required("title")
        if (title.length > MAX_TITLE) throw ToolProblem("A title has $MAX_TITLE characters at most.")
        return Planned("Rename ${desk.name(turn.chat)} to \"$title\"") {
            desk.act(
                target,
                "setChatTitle",
                buildJsonObject {
                    put("chat_id", target.chat)
                    put("title", title)
                },
            )
            "Renamed the group to \"$title\"."
        }
    }
}

@Singleton
@Contribute(Tool::class)
internal class PollTool(desks: TelegramDesks) : TelegramTool(desks) {
    override val definition = ToolDefinition(
        name = "telegram.poll",
        description = "Sends a poll to this chat.",
        parameters = schema(
            listOf("question", "options"),
            "question" to property("string", "The question, 1 to 300 characters."),
            "options" to buildJsonObject {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "The answers, 2 to 10 of 1 to 100 characters each.")
            },
            "anonymous" to property("boolean", "Whether the votes are anonymous (default true)."),
            "multiple" to property("boolean", "Whether a member may pick more than one answer (default false)."),
        ),
        risk = ToolRisk.EXTERNAL_ACTION,
        titleArguments = listOf("question"),
    )

    override suspend fun plan(arguments: JsonObject, turn: TurnInfo, desk: ChatDesk): Planned {
        val target = desk.target(turn.chat)
        val question = arguments.required("question")
        if (question.length > MAX_QUESTION) throw ToolProblem("A question has $MAX_QUESTION characters at most.")
        val options = (arguments["options"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        val fits =
            options?.size in 2..MAX_OPTIONS && options.orEmpty().all { !it.isNullOrEmpty() && it.length <= MAX_OPTION }
        if (options == null || !fits) {
            throw ToolProblem("Give 'options' as 2 to $MAX_OPTIONS answers of 1 to $MAX_OPTION characters.")
        }
        val anonymous = arguments.flag("anonymous") ?: true
        val multiple = arguments.flag("multiple") ?: false
        val kind = listOf(
            if (anonymous) "anonymous" else "votes shown",
            if (multiple) "several answers" else "one answer",
        )
        val shown = "Send a poll to ${desk.name(turn.chat)}: $question\n" +
            options.joinToString("\n") { "- $it" } + "\n(${kind.joinToString(", ")})"
        return Planned(shown) {
            val params = inChat(target) {
                put("question", question)
                put("options", buildJsonArray { options.forEach { add(buildJsonObject { put("text", it) }) } })
                put("is_anonymous", anonymous)
                put("allows_multiple_answers", multiple)
            }
            "Sent the poll as message ${messageId(desk.post(turn.chat, "sendPoll", params))}."
        }
    }
}

@Singleton
@Contribute(Tool::class)
internal class StickerTool(desks: TelegramDesks) : TelegramTool(desks) {
    override val definition = ToolDefinition(
        name = "telegram.sticker",
        description = "Sends a sticker to this chat, by the id a received sticker shows.",
        parameters = schema(listOf("sticker"), "sticker" to property("string", "The sticker's id.")),
        risk = ToolRisk.EXTERNAL_ACTION,
    )

    override suspend fun plan(arguments: JsonObject, turn: TurnInfo, desk: ChatDesk): Planned {
        val target = desk.target(turn.chat)
        val sticker = arguments.required("sticker")
        val shown = "Send sticker $sticker to ${desk.name(turn.chat)}"
        return Planned(shown, RuleSubject(definition.name, emptyList(), "any sticker with telegram.sticker")) {
            val sent = desk.post(turn.chat, "sendSticker", inChat(target) { put("sticker", sticker) })
            "Sent the sticker as message ${messageId(sent)}."
        }
    }
}

@Singleton
@Contribute(Tool::class)
internal class MemberTool(desks: TelegramDesks) : TelegramTool(desks) {
    override val definition = ToolDefinition(
        name = "telegram.member",
        description = "Looks up a member of this chat: their name and whether they are its creator, an admin, a " +
            "member, restricted, gone or banned.",
        parameters = schema(
            listOf("user"),
            "user" to property("string", "The user's id, or the @username of someone who wrote here lately."),
        ),
        risk = ToolRisk.NETWORK_READ,
        titleArguments = listOf("user"),
    )

    override suspend fun plan(arguments: JsonObject, turn: TurnInfo, desk: ChatDesk): Planned {
        val target = desk.target(turn.chat)
        val asked = arguments.required("user")
        val user = desk.user(target.chat, asked)
        return Planned("Look up $asked in ${desk.name(turn.chat)}") {
            val member = desk.read(
                "getChatMember",
                buildJsonObject {
                    put("chat_id", target.chat)
                    put("user_id", user)
                },
            ) as? JsonObject ?: throw ToolProblem("Telegram told nothing of $asked.")
            val found = member["user"] as? JsonObject
            val name = listOfNotNull(found.string("first_name"), found.string("last_name")).joinToString(" ")
            val handle = found.string("username")?.let { "@$it, " }.orEmpty()
            val bot = if ((found?.get("is_bot") as? JsonPrimitive)?.booleanOrNull == true) ", a bot" else ""
            "$name ($handle" + "id $user$bot): ${member.string("status") ?: "unknown"} in ${desk.name(turn.chat)}."
        }
    }
}

private const val MESSAGE = "Which message: \"$THIS\" (the message you answer), \"$QUOTED\" (the message it " +
    "replies to), or a message id a tool gave."

private const val MAX_TITLE = 128

private const val MAX_QUESTION = 300

private const val MAX_OPTIONS = 10

private const val MAX_OPTION = 100

private fun schema(required: List<String> = emptyList(), vararg properties: Pair<String, JsonObject>): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.toMap()))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }

private fun property(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}

/** The argument [name] as text, a number given for it included, null when it is absent. */
private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.required(name: String): String =
    text(name)?.trim()?.ifEmpty { null } ?: throw ToolProblem("Give '$name'.")

private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject?.string(name: String): String? = (this?.get(name) as? JsonPrimitive)?.contentOrNull

/** The parameters of a call in [target], its topic included, with what [more] adds. */
private fun inChat(target: Target, more: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("chat_id", target.chat)
    target.thread?.let { put("message_thread_id", it) }
    more()
}

/** The parameters of a call on [message] of [target], with what [more] adds. */
private fun onMessage(target: Target, message: Long, more: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("chat_id", target.chat)
    put("message_id", message)
    more()
}

private fun messageId(sent: JsonElement): Long =
    ((sent as? JsonObject)?.get("message_id") as? JsonPrimitive)?.longOrNull
        ?: throw ToolProblem("Telegram sent it but told no message id.")
