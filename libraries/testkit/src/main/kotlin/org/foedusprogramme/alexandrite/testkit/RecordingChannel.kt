package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter

/**
 * A channel of the instance [instance] that records what the agent shows and sends, and submits what a test receives
 * through [submitter].
 *
 * A call that breaks the rules of [Channel] or [ReplySink] throws an [AssertionError] and fails the [PluginHarness] run
 * that holds the channel.
 */
public class RecordingChannel(
    public val instance: ChannelInstanceId = TEST_INSTANCE,
    /** The users who are admins of the instance. */
    admins: Set<String> = emptySet(),
    /** How many characters one platform message holds. */
    private val partLength: Int = DEFAULT_PART_LENGTH,
    private val submitter: TurnSubmitter? = null,
) : Channel {
    private val admins = admins.toSet()
    private val lock = Any()
    private val log = MutableStateFlow<List<Event>>(emptyList())
    private val opened = LinkedHashMap<TurnId, RecordedReply>()
    private val capabilities = HashMap<ChatAddress?, ChannelCapabilities>()
    private val deliveries = HashMap<ChatAddress?, ArrayDeque<Delivery>>()
    private val violationList = mutableListOf<String>()
    private var sentParts = 0
    private var inbound = 0

    @Volatile
    internal var stopped: Boolean = false

    init {
        require(partLength > 0) { "A platform message holds at least one character, was $partLength." }
    }

    /** What happened on the channel, in order. */
    public val events: List<Event> get() = log.value

    /** The replies the agent opened, in order. */
    public val replies: List<RecordedReply> get() = synchronized(lock) { opened.values.toList() }

    public val sent: List<Event.Sent> get() = events.filterIsInstance<Event.Sent>()

    public val violations: List<String> get() = synchronized(lock) { violationList.toList() }

    /** The reply opened for [turn]. */
    public fun reply(turn: TurnId): RecordedReply =
        synchronized(lock) { opened[turn] } ?: throw NoSuchElementException("No reply of turn $turn was opened.")

    /** Suspends until a reply is opened for [turn], and returns it. */
    public suspend fun awaitReply(turn: TurnId): RecordedReply {
        awaitEvent { it is Event.Opened && it.request.turn.id == turn }
        return reply(turn)
    }

    /** Suspends until an event that [predicate] accepts is recorded, and returns the first such event. */
    public suspend fun awaitEvent(predicate: (Event) -> Boolean): Event =
        log.mapNotNull { events -> events.firstOrNull(predicate) }.first()

    /** What the channel can do in [chat], or in every chat that has none of its own when [chat] is null. */
    public fun scriptCapabilities(capabilities: ChannelCapabilities, chat: ChatAddress? = null) {
        synchronized(lock) { this.capabilities[chat] = capabilities }
    }

    /**
     * Queues the deliveries of the next replies and sends to [chat], or to any chat when it is null. An unscripted
     * delivery reaches the chat as one platform message per part.
     */
    public fun scriptDeliveries(vararg deliveries: Delivery, chat: ChatAddress? = null) {
        synchronized(lock) { this.deliveries.getOrPut(chat) { ArrayDeque() }.addAll(deliveries) }
    }

    public fun chat(chat: String = "chat", thread: String? = null): ChatAddress = ChatAddress(instance, chat, thread)

    /** A human user, an admin when [user] is one of the instance's admins. */
    public fun user(user: String = "member"): ChatUser = testUser(user, user in admins, instance)

    /** A message of [sender] in [chat], numbered `in-<n>`, changed by [block]. */
    public fun message(
        text: String,
        chat: ChatAddress = chat(),
        sender: ChatUser = user(),
        block: IncomingMessage.Builder.() -> Unit = {},
    ): IncomingMessage = testMessage(text, chat, sender, "in-${synchronized(lock) { ++inbound }}", block)

    /** An invocation of the command [name] in [chat], carried by a message numbered `in-<n>`. */
    public fun command(
        name: String,
        arguments: String = "",
        chat: ChatAddress = chat(),
        issuer: ChatUser = user(),
    ): CommandInvocation = carried(name, arguments, chat, issuer).invocation

    /** Submits a message of [sender] in [chat], changed by [block]. */
    public fun receive(
        text: String,
        chat: ChatAddress = chat(),
        sender: ChatUser = user(),
        block: IncomingMessage.Builder.() -> Unit = {},
    ): Admission = submit(Submission.Message(message(text, chat, sender, block)))

    /** Submits an invocation of the command [name] with the message `/<name> <arguments>` that carried it. */
    public fun receiveCommand(
        name: String,
        arguments: String = "",
        chat: ChatAddress = chat(),
        issuer: ChatUser = user(),
    ): Admission = submit(carried(name, arguments, chat, issuer))

    /** Records [submission] as received and submits it. */
    public fun submit(submission: Submission): Admission {
        require(submission.chat.instance == instance) {
            "Recording channel $instance cannot submit a submission of ${submission.chat}."
        }
        val target = checkNotNull(submitter) {
            "Recording channel $instance has no TurnSubmitter: run the agent, or give the harness a " +
                "RecordingTurnSubmitter."
        }
        synchronized(lock) { record(Event.Received(submission)) }
        return target.submit(submission)
    }

    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities = synchronized(lock) {
        checkChat(chat, "a question for the capabilities")
        capabilities[chat] ?: capabilities[null] ?: DEFAULT_CAPABILITIES
    }

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int = synchronized(lock) {
        checkChat(chat, "a question for the parts needed")
        parts(text)
    }

    override suspend fun openReply(request: ReplyRequest): ReplySink {
        val turn = request.turn
        val reply = synchronized(lock) {
            violation(
                when {
                    stopped -> "a reply of turn ${turn.id} opened after the instance stopped."
                    turn.chat.instance != instance -> "a reply of turn ${turn.id} opened for ${turn.chat}."
                    turn.replyTarget != ReplyTarget.CHAT -> "a reply of turn ${turn.id}, which replies to its caller."
                    turn.id in opened -> "a second reply of turn ${turn.id}."
                    else -> null
                },
            )
            record(Event.Opened(request))
            RecordedReply(request, this).also { opened[turn.id] = it }
        }
        return Sink(reply)
    }

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = synchronized(lock) {
        checkChat(chat, "a message sent")
        delivery(chat, message, replying = false).also { record(Event.Sent(chat, message, it)) }
    }

    internal fun <T> read(block: () -> T): T = synchronized(lock, block)

    private fun carried(name: String, arguments: String, chat: ChatAddress, issuer: ChatUser): Submission.Command {
        val message = message("/$name $arguments".trimEnd(), chat, issuer)
        val invocation = CommandInvocation.builder(chat, name, issuer).arguments(arguments).trigger(message.ref).build()
        return Submission.Command(invocation, message)
    }

    private fun checkChat(chat: ChatAddress, what: String) {
        violation(
            when {
                stopped -> "$what for $chat after the instance stopped."
                chat.instance != instance -> "$what for $chat, a chat of another instance."
                else -> null
            },
        )
    }

    private fun parts(text: String): Int = maxOf(1, (text.length + partLength - 1) / partLength)

    /** How [message] reaches [chat], a reply of a turn when [replying]. */
    private fun delivery(chat: ChatAddress, message: OutboundMessage, replying: Boolean): Delivery {
        deliveries[chat]?.removeFirstOrNull()?.let { return it }
        deliveries[null]?.removeFirstOrNull()?.let { return it }
        val parts = parts(message.text)
        val limit = (capabilities[chat] ?: capabilities[null] ?: DEFAULT_CAPABILITIES).maxPartsPerReply
        if (replying && limit != null && parts > limit) {
            return Delivery.NotDelivered(DeliveryFailure.TOO_LONG, "$parts parts exceed the limit of $limit.")
        }
        return Delivery.Delivered(List(parts) { ChannelMessageRef(chat, "out-${++sentParts}") })
    }

    /** Records [problem] and throws it, unless it is null. */
    private fun violation(problem: String?) {
        if (problem == null) return
        val message = "Recording channel $instance saw $problem"
        violationList += message
        throw AssertionError(message)
    }

    /** Appends [event] to the log, under the lock so that the log keeps the order of the calls. */
    private fun record(event: Event) {
        log.update { it + event }
    }

    private inner class Sink(private val reply: RecordedReply) : ReplySink {
        private val turn = reply.turn.id

        override suspend fun preview(segment: Int, text: String) {
            synchronized(lock) {
                violation(
                    when {
                        stopped -> "a preview of turn $turn after the instance stopped."

                        reply.end != null -> "a preview of turn $turn after its reply ended."

                        segment < 0 -> "a preview of turn $turn in segment $segment."

                        segment < reply.segment ->
                            "a preview of turn $turn in segment $segment after segment " +
                                "${reply.segment} froze it."

                        else -> null
                    },
                )
                reply.segment = segment
                reply.previewed += RecordedReply.Preview(segment, text)
                record(Event.Previewed(turn, segment, text))
            }
        }

        override suspend fun complete(message: OutboundMessage): Delivery = synchronized(lock) {
            checkOpen("completed")
            val delivery = delivery(reply.turn.chat, message, replying = true)
            reply.end = Event.Completed(turn, message, delivery).also(::record)
            delivery
        }

        override suspend fun abandon(end: ReplyEnd) {
            synchronized(lock) {
                checkOpen("abandoned")
                reply.end = Event.Abandoned(turn, end).also(::record)
            }
        }

        private fun checkOpen(how: String) {
            violation(
                when {
                    stopped -> "the reply of turn $turn $how after the instance stopped."
                    reply.end != null -> "the reply of turn $turn $how after it ended."
                    else -> null
                },
            )
        }
    }

    /** Something that happened on a [RecordingChannel]. */
    public sealed interface Event {
        public data class Opened(public val request: ReplyRequest) : Event

        public data class Previewed(public val turn: TurnId, public val segment: Int, public val text: String) : Event

        public data class Completed(
            public val turn: TurnId,
            public val message: OutboundMessage,
            public val delivery: Delivery,
        ) : Event

        public data class Abandoned(public val turn: TurnId, public val end: ReplyEnd) : Event

        public data class Sent(
            public val chat: ChatAddress,
            public val message: OutboundMessage,
            public val delivery: Delivery,
        ) : Event

        /** A submission the test made through the channel. */
        public data class Received(public val submission: Submission) : Event
    }

    public companion object {
        public const val DEFAULT_PART_LENGTH: Int = 4096

        /** Streaming with the final message in place of the previews, proactive, and in plain text or Markdown. */
        public val DEFAULT_CAPABILITIES: ChannelCapabilities = ChannelCapabilities.builder()
            .streaming(true)
            .finalReplacesPreview(true)
            .proactive(true)
            .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
            .build()
    }
}

/** The reply of one turn on a [RecordingChannel]. */
public class RecordedReply internal constructor(
    public val request: ReplyRequest,
    private val channel: RecordingChannel,
) {
    internal var segment = 0
    internal val previewed = mutableListOf<Preview>()
    internal var end: RecordingChannel.Event? = null

    public val turn: TurnInfo get() = request.turn

    public val previews: List<Preview> get() = channel.read { previewed.toList() }

    /** The last preview of each segment. */
    public val segments: Map<Int, String> get() = previews.associate { it.segment to it.text }

    /** The final message, null until the reply completes. */
    public val completed: OutboundMessage? get() = channel.read { (end as? RecordingChannel.Event.Completed)?.message }

    public val delivery: Delivery? get() = channel.read { (end as? RecordingChannel.Event.Completed)?.delivery }

    /** Why the reply ended without a final message, null unless it did. */
    public val abandoned: ReplyEnd? get() = channel.read { (end as? RecordingChannel.Event.Abandoned)?.end }

    public val ended: Boolean get() = channel.read { end != null }

    /** Suspends until the reply completes or is abandoned, and returns it. */
    public suspend fun awaitEnd(): RecordedReply {
        val id = turn.id
        channel.awaitEvent {
            (it is RecordingChannel.Event.Completed && it.turn == id) ||
                (it is RecordingChannel.Event.Abandoned && it.turn == id)
        }
        return this
    }

    public data class Preview(public val segment: Int, public val text: String)
}
