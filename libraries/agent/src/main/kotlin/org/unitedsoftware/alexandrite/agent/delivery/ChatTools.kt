package org.unitedsoftware.alexandrite.agent.delivery

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.permission.Principal
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.agent.tool.AgentToolContext
import org.unitedsoftware.alexandrite.agent.tool.CompanionTool
import org.unitedsoftware.alexandrite.agent.tool.property
import org.unitedsoftware.alexandrite.agent.tool.text
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.time.Clock

/** A chat that an agent may send to, with how people know it and whether it is one of the agent's homes. */
internal class ChatTarget(val chat: ChatAddress, val title: String?, val home: Boolean)

/**
 * The chats each agent may send to with `chat.send`: its homes, the chats where it has a conversation and the chats
 * linked with them, on the channel instances it is attached to.
 */
@Singleton
internal class ChatTargets(
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
    private val conversations: ConversationStore,
    private val channels: ChannelDirectory,
) {
    /** The chats [agent] may send to, its homes first. */
    suspend fun of(agent: AgentId): List<ChatTarget> {
        val instances = directory.agent(agent)?.attachments.orEmpty().map { it.instance }
        val homes = instances.mapNotNull { topology.home(agent, it) }
        val spoken = conversations.chats(agent).flatMap { topology.group(agent, it)?.chats ?: listOf(it) }
        return (homes + spoken.sortedBy { "$it" }).distinct()
            .filter { it.instance in instances }
            .map { ChatTarget(it, title(it), it in homes) }
    }

    /**
     * The chat [to] names for [turn]: `home` (the agent's home on the turn's channel instance, else its only home),
     * `here` (the turn's chat) or a chat address among [of]'s.
     */
    suspend fun resolve(turn: TurnInfo, to: String): ChatTarget {
        val agent = turn.agent
        val targets = of(agent)
        val chat = when (to.trim()) {
            HOME -> {
                val homes = targets.filter { it.home }
                homes.firstOrNull { it.chat.instance == turn.chat.instance }?.chat
                    ?: homes.singleOrNull()?.chat
                    ?: throw ToolRefusal(
                        if (homes.isEmpty()) {
                            "You have no home chat: name a chat (see chat.list)."
                        } else {
                            "You have a home chat on each of several channels, none of them this turn's: name one of " +
                                "${homes.joinToString { "${it.chat}" }}."
                        },
                    )
            }

            HERE -> turn.chat

            else -> ChatAddress.parseOrNull(to.trim()) ?: throw ToolRefusal("'$to' is no chat: see chat.list.")
        }
        return targets.firstOrNull { it.chat == chat } ?: throw ToolRefusal(
            "You cannot send to $chat: send only to a chat you serve, " + listed(targets),
        )
    }

    private suspend fun title(chat: ChatAddress): String? = try {
        channels.channel(chat.instance)?.title(chat)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val HOME = "home"
        const val HERE = "here"

        /** The chats of [targets] as a sentence end. */
        fun listed(targets: List<ChatTarget>): String =
            if (targets.isEmpty()) "and you serve none yet." else "such as ${targets.joinToString { "${it.chat}" }}."
    }
}

/** Lists the chats that `chat.send` reaches. */
@Singleton
@Contribute(Tool::class)
internal class ChatListTool(private val targets: ChatTargets) :
    Tool,
    CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "chat.list",
        description = "Lists the chats you can send a message to with chat.send: your home chats, the chats where " +
            "you have a conversation and the chats linked with them, on every channel you serve.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("additionalProperties", false)
        },
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val turn = context.turn
        val listed = targets.of(turn.agent)
        if (listed.isEmpty()) return ToolResult("You serve no chat yet, so chat.send reaches none.")
        val lines = listed.map { target ->
            val notes = listOfNotNull("home".takeIf { target.home }, "this chat".takeIf { target.chat == turn.chat })
            val title = target.title?.let { " \"$it\"" }.orEmpty()
            val noted = if (notes.isEmpty()) "" else " (${notes.joinToString()})"
            "- ${target.chat}$title$noted"
        }
        return ToolResult((listOf("The chats you can send to:") + lines).joinToString("\n"))
    }
}

/**
 * Sends a message from the agent to a chat it serves, on any channel. Without asking anyone when the turn is an
 * operator's or nobody's and its conversation is untainted, as that tells the chat no more than a reply there could.
 */
@Singleton
@Contribute(Tool::class)
internal class ChatSendTool(
    private val targets: ChatTargets,
    private val topology: ChatTopology,
    private val conversations: ConversationStore,
    private val channels: ChannelDirectory,
    private val sent: SentMessages,
    private val clock: Clock,
) : ReviewedTool,
    CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "chat.send",
        description = "Sends a message to one of your chats, on any channel: 'home' for your home chat, or a chat " +
            "address from chat.list. Your reply already reaches the chat you are answering: use this to reach " +
            "another chat, or any chat from a turn whose reply goes nowhere, such as a heartbeat. The chat's next " +
            "conversation turn is told what you sent.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                property("to", "string", "'home', 'here' or a chat address such as telegram:main:1234.")
                property("text", "string", "The message, in Markdown.")
            }
            put("required", JsonArray(listOf(JsonPrimitive("to"), JsonPrimitive("text"))))
            put("additionalProperties", false)
        },
        risk = ToolRisk.EXTERNAL_ACTION,
        activity = ToolActivity.SEND,
        titleArguments = listOf("to"),
    )

    /** How many messages each root turn sent, for the newest [KEPT_TURNS] turns. */
    private val counts = object : LinkedHashMap<TurnId, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TurnId, Int>) = size > KEPT_TURNS
    }

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = try {
        val request = requested(arguments, context.turn)
        val untainted = (context as? AgentToolContext)?.taint?.tainted == false
        val title = request.target.title?.let { " \"$it\"" }.orEmpty()
        CallReview.builder()
            .preview(ReviewPreview.Text("Send to ${request.target.chat}$title:\n\n${request.text}"))
            .exempt(untainted && Principal.of(context.turn) != Principal.GUEST)
            .build()
    } catch (e: ToolRefusal) {
        CallReview.builder().problem(e.message).build()
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = try {
        val turn = context.turn
        val request = requested(arguments, turn)
        val chat = request.target.chat
        val root = turn.lineage?.rootTurn ?: turn.id
        synchronized(counts) {
            val count = counts[root] ?: 0
            if (count >= MAX_SENDS) throw ToolRefusal("This turn sent $MAX_SENDS messages already, the most it may.")
            counts[root] = count + 1
        }
        send(turn, chat, request.text, taintOf(context))
    } catch (e: ToolRefusal) {
        ToolResult(e.message, isError = true)
    }

    /** Why a message that [context]'s call sends taints the conversation it reaches, null when it does not. */
    private fun taintOf(context: ToolContext): String? {
        val taint = (context as? AgentToolContext)?.taint ?: return null
        val why = taint.known?.reason ?: return null
        return "a message sent here from tainted conversation ${context.turn.conversation} ($why)"
    }

    private suspend fun send(turn: TurnInfo, chat: ChatAddress, text: String, taint: String?): ToolResult {
        val channel = channels.channel(chat.instance)
            ?: return ToolResult("The channel of $chat is not open, so nothing was sent.", isError = true)
        val delivery = delivered {
            val markdown = Markup.MARKDOWN in channel.capabilities(chat).markups
            val message = OutboundMessage.builder(text, MessageKind.REPLY)
                .markup(if (markdown) Markup.MARKDOWN else Markup.PLAIN)
                .conversation(turn.conversation)
                .build()
            channel.send(chat, message)
        }
        return when (delivery) {
            is Delivery.Delivered -> {
                logger.info("Turn {} of {} sent a message to {}", turn.id, turn.key, chat)
                val key = topology.key(turn.agent, chat)
                if (key != turn.key) {
                    val at = clock.instant().toString()
                    sent.add(conversations.current(key).id, SentMessage(at, origin(turn), text, taint))
                }
                val ids = delivery.messages.joinToString { it.id }
                ToolResult("Sent to $chat${if (ids.isEmpty()) "" else " as message $ids"}.")
            }

            is Delivery.NotDelivered -> {
                logger.warn("Turn {} of {} could not send a message to {}: {}", turn.id, turn.key, chat, delivery)
                val detail = delivery.detail?.let { ": ${it.lineSequence().first()}" }.orEmpty()
                ToolResult("The message did not reach $chat: ${delivery.kind}$detail.", isError = true)
            }
        }
    }

    /** What the call of [arguments] in [turn] asks to send where, refused where it may not go. */
    private suspend fun requested(arguments: JsonObject, turn: TurnInfo): Request {
        val to = arguments.text("to") ?: throw ToolRefusal("Give 'to': 'home', 'here' or a chat (see chat.list).")
        val text = arguments.text("text")?.takeIf { it.isNotBlank() }
            ?: throw ToolRefusal("Give 'text', the message to send.")
        val target = targets.resolve(turn, to)
        if (target.chat == turn.chat && turn.replyTarget == ReplyTarget.CHAT) {
            throw ToolRefusal("Your reply already reaches ${turn.chat}: say it there instead.")
        }
        if (!channels.proactive(target.chat)) {
            throw ToolRefusal("${target.chat} takes no messages it did not ask for.")
        }
        return Request(target, text)
    }

    /** Where [turn] sends from, as the chat it sends to is told. */
    private fun origin(turn: TurnInfo): String = when (turn.kind) {
        TurnKind.HEARTBEAT -> "a heartbeat"
        TurnKind.REMINDER -> "a scheduled task of ${turn.chat}"
        else -> "your conversation in ${turn.chat}"
    }

    private class Request(val target: ChatTarget, val text: String)

    private companion object {
        const val MAX_SENDS = 10
        const val KEPT_TURNS = 1024
    }
}

private val logger = LoggerFactory.getLogger(ChatSendTool::class.java)
