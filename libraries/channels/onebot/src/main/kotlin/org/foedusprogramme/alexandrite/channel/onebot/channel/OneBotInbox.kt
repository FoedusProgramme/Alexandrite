package org.foedusprogramme.alexandrite.channel.onebot.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.foedusprogramme.alexandrite.channel.onebot.mapping.OneBotChats
import org.foedusprogramme.alexandrite.channel.onebot.mapping.OneBotMessages
import org.foedusprogramme.alexandrite.channel.onebot.mapping.QuotedMessage
import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.GetMessage
import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.OneBotMessageInfo
import org.foedusprogramme.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.foedusprogramme.alexandrite.channel.onebot.protocol.message.OneBotSegment
import org.foedusprogramme.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.foedusprogramme.alexandrite.channel.onebot.transport.OneBotWire
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * What the implementation reports, taken into the agent.
 *
 * A message the bot is addressed by, and a command wherever it appears, becomes a submission that the agent queues as
 * a turn of its chat. Addressed means what it means to every channel of this project: a direct chat always, and a
 * group when the message names the bot or answers one of its messages. Everything else a group says is not a turn,
 * which is what keeps the traffic of a group out of the agent.
 *
 * Every other event, a notice, a request or a heartbeat, is read and dropped here: it is not a turn, and this version
 * has no way to hand one to another plugin. The connection can carry it, so the way out is a decision about the shape
 * of that interface rather than about this class, and until it is taken a plugin that needs a notice or a request goes
 * through [OneBotApi] for the state it wants. An instance whose runtime has no agent accepts the events and hands them
 * nowhere, which is reported once so that the silence is visible rather than puzzling.
 */
@ChannelInstanceScoped
internal class OneBotInbox(
    private val instance: ChannelInstance,
    private val config: OneBotInstanceConfig,
    private val link: OneBotLink,
    /** What this instance sends, which answers an approval command. */
    private val sender: OneBotSender,
    /** The approvals this instance asks its admins about. */
    private val approvals: OneBotApprovals,
    /** The messages this instance sent, which tell a reply to the bot from a reply to somebody else. */
    private val sent: OneBotSentMessages,
    /** What this instance read not long ago, which is what a message answering one says it answered. */
    private val recent: OneBotRecentMessages,
    /** The agent, null while the runtime has none. */
    private val submitter: TurnSubmitter?,
) : Lifecycle {
    private var reading: Job? = null

    override suspend fun onStart() {
        if (submitter == null) {
            logger.warn(
                "{}: the runtime has no agent, so the messages of channel instance {} reach nobody",
                PLUGIN,
                instance.id,
            )
            return
        }
        reading = instance.scope.launch { read() }
    }

    override suspend fun onClose() {
        reading?.cancel()
        reading = null
    }

    private suspend fun read() {
        link.events.collect { event ->
            if (event !is OneBotEvent.Message) return@collect
            val chat = OneBotChats.addressOf(instance.id, event)
            // Every message is kept as it arrives, whether or not it is meant for the bot: what a later message answers
            // is usually one of these, and the one it answers need not have been meant for the bot at all.
            recent.remember(chat, event.messageId.value, OneBotMessages.quotable(instance.id, event))
            // A command is run wherever it is written; anything else has to be meant for the bot, or a group's talk
            // would each become a turn of the agent.
            val command = OneBotCommands.of(event)
            if (command == null && !addressed(event)) return@collect
            // An approval is answered here rather than by the agent: the command registry of the runtime knows nothing
            // of it, and a command it does not claim is refused to everyone but an operator.
            if (command != null && approvals.answers(command.name)) {
                val asked = OneBotMessages.senderOf(instance.id, event, config.admins)
                val answer = approvals.answer(event, command, asked)
                if (answer != null) {
                    sender.send(chat, said(answer))
                }
                return@collect
            }
            val message = OneBotMessages.incoming(instance.id, event, config.admins, quoted(chat, event))
            val submission = if (command == null) Submission.Message(message) else commandOf(command, message)
            report(message, submitter?.submit(submission))
        }
    }

    /**
     * What [event] answers, as the channel read it, null when it answers nothing.
     *
     * What the channel kept is read first, which is what a chat normally answers. Only a message older than what is
     * kept is read back from the implementation, and that read can fail: a message may be withdrawn, or unknown to the
     * implementation, and a chat goes on whether or not what a message quoted can be read.
     */
    private suspend fun quoted(chat: ChatAddress, event: OneBotEvent.Message): QuotedMessage? {
        val id = event.message.segments.filterIsInstance<OneBotSegment.Reply>().firstOrNull()?.id ?: return null
        recent.find(chat, id.value)?.let { return it }
        val result = try {
            link.call("get_msg", GetMessage(id).toJson())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("{} cannot read what message {} answers: {}", instance.id, event.messageId, e.toString())
            return null
        }
        val data = (result as? OneBotResult.Ok)?.data ?: return null
        return try {
            val info = OneBotWire.decodeFromJsonElement(OneBotMessageInfo.serializer(), data)
            OneBotMessages.quotable(instance.id, info, event.selfId.value).also { recent.remember(chat, id.value, it) }
        } catch (e: Exception) {
            logger.debug("{} cannot read the report about message {}: {}", instance.id, id, e.toString())
            null
        }
    }

    /** The message that says [text] as the answer to an approval command. */
    private fun said(text: String): OutboundMessage = OutboundMessage.builder(text, MessageKind.REPLY).build()

    /** The submission of [command] in [message], which an operator's commands pass the queue with. */
    private fun commandOf(command: OneBotCommands.Parsed, message: IncomingMessage): Submission.Command =
        Submission.Command(
            invocation = CommandInvocation.builder(message.chat, command.name, message.sender)
                .arguments(command.arguments)
                .trigger(message.ref)
                .build(),
            message = message,
            capacity = if (message.sender.isAdmin) Capacity.EXEMPT else Capacity.COUNTED,
        )

    /** Whether the bot of this instance is addressed by [event]. */
    private fun addressed(event: OneBotEvent.Message): Boolean = OneBotCommands.addressed(
        event = event,
        isDirect = event is OneBotEvent.Message.Private,
        namesTheBot = { selfId -> event.message.segments.any { it is OneBotSegment.At && it.names(selfId) } },
        answersTheBot = { id -> sent.holds(id) },
    )

    /**
     * Reports a submission the agent refused.
     *
     * A message the agent refuses never becomes a turn, so it is said here rather than dropped in silence: the reason
     * is what tells an operator that the chat is not linked, which nothing else reports.
     */
    private fun report(message: IncomingMessage, admission: Admission?) {
        if (admission !is Admission.Refused) return
        logger.warn(
            "{}: instance {} took a message of {} no further: {}",
            PLUGIN,
            instance.id,
            message.chat,
            admission.reason,
        )
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-onebot"

        val logger: Logger = LoggerFactory.getLogger(OneBotInbox::class.java)
    }
}

/**
 * The commands of a message, and whether the bot is addressed by one.
 *
 * A command starts a message and is named after a `/`, which is how every channel of this project spells one: the
 * prefix is configurable nowhere yet, so it is not configurable here either. A command is taken whether or not the bot
 * is named, because naming it is what a command may do to be sure of a bot, not what makes it a command.
 */
internal object OneBotCommands {
    /** The prefix a command is named after. */
    const val PREFIX = "/"

    /** The command [event] carries, null when its text names none. */
    fun of(event: OneBotEvent.Message): Parsed? {
        val text = textOf(event).trimStart()
        val token = text.takeWhile { !it.isWhitespace() }
        if (!token.startsWith(PREFIX) || token.length == PREFIX.length) return null
        return Parsed(token.removePrefix(PREFIX), text.removePrefix(token).trim())
    }

    /**
     * Whether [event] is a message the bot is addressed by.
     *
     * A direct chat is the bot's. A group is the bot's when the message names it or answers one of its messages, and
     * what it answers is told by [answersTheBot] rather than by the message, which names the id it answers and not
     * the author of it.
     */
    fun addressed(
        event: OneBotEvent.Message,
        isDirect: Boolean,
        namesTheBot: (String) -> Boolean,
        answersTheBot: (String) -> Boolean,
    ): Boolean {
        if (isDirect) return true
        val selfId = event.selfId.value
        if (namesTheBot(selfId)) return true
        val answered = event.message.segments.filterIsInstance<OneBotSegment.Reply>().firstOrNull() ?: return false
        return answersTheBot(answered.id.value)
    }

    /** The text of [event], which is every text segment of it and nothing else. */
    private fun textOf(event: OneBotEvent.Message): String =
        event.message.segments.filterIsInstance<OneBotSegment.Text>().joinToString("") { it.text }

    /** The parts of a command: its [name] and its [arguments], which may be empty. */
    internal class Parsed(val name: String, val arguments: String)
}

/** Whether [this] mention names [selfId], or names everyone. */
private fun OneBotSegment.At.names(selfId: String): Boolean = isAll || userId == selfId
