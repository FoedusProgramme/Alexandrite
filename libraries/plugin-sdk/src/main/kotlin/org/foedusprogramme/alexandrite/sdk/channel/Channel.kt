package org.foedusprogramme.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi

/** A channel instance's front end, contributed once by a channel plugin under the plugin's channel type. */
@ContributedSpi(named = true)
public interface Channel {
    /** What the channel can do in [chat]. */
    public suspend fun capabilities(chat: ChatAddress): ChannelCapabilities

    /** How many platform messages [text] in [markup] takes in [chat]. */
    public suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int

    /** Opens the reply of a turn that delivers to its chat, once the agent dequeues the turn. */
    public suspend fun openReply(request: ReplyRequest): ReplySink

    /** Sends [message] to [chat] outside any turn's reply. */
    public suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery

    /** How people know [chat], such as a group's title, null where the channel knows no name for it. */
    public suspend fun title(chat: ChatAddress): String? = null

    /**
     * Asks an approver about [request] and waits for the answer, within the channel's own limit.
     *
     * The agent withdraws a request by cancelling the call: the channel then marks it withdrawn and rethrows.
     */
    public suspend fun askApproval(request: ApprovalRequest): ApprovalAnswer =
        ApprovalAnswer.Unavailable("this channel cannot ask for approvals")

    /** Whether [issuer] may run [command] in [chat], bare or with arguments; true where the channel sets no rules. */
    public fun mayRun(chat: ChatAddress, issuer: ChatUser, command: String, withArguments: Boolean): Boolean = true
}

/** The reply of one turn, which delivers until the channel instance stops and then takes calls without delivering. */
public interface ReplySink {
    /** Shows [text], the whole text of [segment] so far, where a higher segment freezes the earlier ones. */
    public suspend fun preview(segment: Int, text: String)

    /** Starts [segment], which freezes the earlier ones, before its first preview if it gets any. */
    public suspend fun startSegment(segment: Int) {}

    /** Shows [event], of a kind the channel's capabilities declare in [ChannelCapabilities.events]. */
    public suspend fun event(event: TurnEvent) {}

    /**
     * Hands the activity of the background [run] to the caller, to go on showing where the reply shows the run's
     * delegating call after the reply ends; null when the channel cannot, or no longer shows that call.
     */
    public suspend fun detachActivity(run: DelegatedRun): ReplyActivity? = null

    /** Delivers the final reply, in place of the previews where the channel can. */
    public suspend fun complete(message: OutboundMessage): Delivery

    /** Ends the reply without a final message and leaves the previews as they are. */
    public suspend fun abandon(end: ReplyEnd)
}

/** A sub-agent [run] that [agent] runs for the tool call [call] of the run [under], or of the turn itself. */
@Poko
public class DelegatedRun(
    public val call: ToolCallId,
    public val run: RunId,
    public val agent: AgentId,
    public val under: RunId? = null,
)

/**
 * The activity of a background run, which outlives the reply that handed it over.
 *
 * The agent calls it from one coroutine at a time and calls [close] exactly once, after which the channel ignores it.
 */
public interface ReplyActivity {
    /** Shows [event], a [TurnEvent.ToolCallStarted] or [TurnEvent.ToolCallUpdated] of a call that the run makes. */
    public suspend fun event(event: TurnEvent)

    /** Shows the run's delegating call as ended with [status] and lets the reply's resources go. */
    public suspend fun close(status: ToolCallStatus)
}

/** What a channel needs to open a turn's reply. */
@Poko
public class ReplyRequest(
    public val turn: TurnInfo,
    /** The message or interaction the turn answers, null when it answers none. */
    public val trigger: ChannelMessageRef?,
    /** The background run of [turn] whose activity alone this reply shows, null for the turn's own reply. */
    public val run: DelegatedRun? = null,
) {
    @Deprecated(message = "Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(turn: TurnInfo, trigger: ChannelMessageRef?) : this(turn, trigger, null)
}

/** Why a reply ended without a final message. */
@JvmInline
@Serializable
public value class ReplyEnd internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The reply showed only the activity of a background run, which is done. */
        public val COMPLETED: ReplyEnd = ReplyEnd("completed")

        public val CANCELLED: ReplyEnd = ReplyEnd("cancelled")

        public val FAILED: ReplyEnd = ReplyEnd("failed")

        /** The runtime is stopping. */
        public val SHUTDOWN: ReplyEnd = ReplyEnd("shutdown")

        /** The values this version knows. */
        public val entries: List<ReplyEnd> = listOf(COMPLETED, CANCELLED, FAILED, SHUTDOWN)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ReplyEnd = ReplyEnd(id)
    }
}
