package org.foedusprogramme.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry

/** Takes the user input of channels, where submit order is turn order per chat. */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnSubmitter {
    /** Queues [submission] and returns at once. */
    public fun submit(submission: Submission): Admission
}

/** User input that a channel submits. */
public sealed interface Submission {
    public val chat: ChatAddress
    public val capacity: Capacity

    @Poko
    public class Message(public val message: IncomingMessage, override val capacity: Capacity = Capacity.COUNTED) :
        Submission {
        override val chat: ChatAddress get() = message.chat
    }

    /** A command invocation, which becomes a turn of kind COMMAND. */
    @Poko
    public class Command(
        public val invocation: CommandInvocation,
        /** The message that carried the invocation, null when none did. */
        public val message: IncomingMessage? = null,
        override val capacity: Capacity = Capacity.COUNTED,
    ) : Submission {
        init {
            require(message == null || message.ref == invocation.trigger) {
                "The message that carries a command invocation is its trigger, was ${message?.ref} for " +
                    "${invocation.trigger}."
            }
            require(message == null || message.sender.address == invocation.issuer.address) {
                "The message that carries a command invocation is the issuer's, was ${message?.sender?.address} " +
                    "for ${invocation.issuer.address}."
            }
        }

        override val chat: ChatAddress get() = invocation.chat
    }
}

/** Whether a submission counts against its chat's queue bound. */
public enum class Capacity {
    /** Admitted while the queue is below its bound. */
    COUNTED,

    /** Admitted past the bound, as replays and an admin's commands are. */
    EXEMPT,
}

/** What the agent made of a submission. */
public sealed interface Admission {
    /** Queued as the turn of [ticket]. */
    @Poko
    public class Accepted(public val ticket: TurnTicket) : Admission

    @Poko
    public class Refused(
        public val reason: RefusalReason,
        /** The bound of the full queue, null when the queue was not full. */
        public val queueCapacity: Int? = null,
    ) : Admission {
        init {
            require(queueCapacity == null || queueCapacity > 0) { "A queue capacity is positive, was $queueCapacity." }
        }
    }
}

/** Why the agent refused a submission. */
@JvmInline
@Serializable
public value class RefusalReason internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The chat's queue holds as many turns as it may. */
        public val QUEUE_FULL: RefusalReason = RefusalReason("queue_full")

        /** The agent is closing. */
        public val SHUTTING_DOWN: RefusalReason = RefusalReason("shutting_down")

        /** The chat's channel instance is not configured. */
        public val UNKNOWN_CHAT: RefusalReason = RefusalReason("unknown_chat")

        /** No agent serves the chat. */
        public val NO_AGENT: RefusalReason = RefusalReason("no_agent")
    }
}

/** A queued turn. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnTicket {
    public val turn: TurnId

    /** Suspends until the turn ends, which a turn of the same chat must never wait for. */
    public suspend fun outcome(): TurnOutcome

    /** Drops the turn while it is queued or cancels it while it runs, and returns whether it had not ended. */
    public fun cancel(): Boolean
}

/** How a turn ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnOutcome {
    /** Whether the turn left nothing in its history or its chat, so it may be submitted again after a restart. */
    public val replayable: Boolean

    /** The turn ran to its end. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Completed(
        /** The model's final response, null when the turn ended on a notice or ran a command. */
        public val reply: AssistantEntry?,
        /** How the reply reached each chat it was sent to. */
        public val deliveries: Map<ChatAddress, Delivery> = emptyMap(),
    ) : TurnOutcome {
        override val replayable: Boolean get() = false
    }

    /** The message joined the running turn [into] as a follow-up. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Absorbed(public val into: TurnId) : TurnOutcome {
        override val replayable: Boolean get() = false
    }

    /** The model answered with nothing, so the turn's input was taken back and the chat got a notice. */
    @OptIn(InternalAlexandriteApi::class)
    public data object TakenBack : TurnOutcome {
        override val replayable: Boolean get() = false
    }

    @OptIn(InternalAlexandriteApi::class)
    public data object Cancelled : TurnOutcome {
        override val replayable: Boolean get() = false
    }

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Failed(public val message: String) : TurnOutcome {
        override val replayable: Boolean get() = false
    }

    /** The runtime stopped before the turn started, or cut it off. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ShutDown(override val replayable: Boolean) : TurnOutcome
}
