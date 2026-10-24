package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import java.time.Instant

/** The turns and jobs of the agents, controlled without a permission check, recording who asked as `by`. */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnControl {
    /**
     * The turns that the agent's workers run or queue and the commands that run, at [chat] or everywhere when null,
     * delegated runs included, where a queued turn shows the conversation it would start in now.
     *
     * Messages that a chat's intake holds and commands that wait in its command lane are left out.
     */
    public suspend fun turns(chat: ChatAddress? = null): List<TurnStatus>

    /**
     * Cancels the running turn from [chat] of [agent], else of the chat's agent, and every run it delegated, leaving
     * its jobs and queued turns alone.
     */
    public fun cancel(chat: ChatAddress, by: ChatUser?, agent: AgentId? = null): Boolean

    /** Drops [turn] while it is queued, held or waiting, or cancels it while it runs. */
    public fun cancelTurn(turn: TurnId, by: ChatUser?): Boolean

    /** Cancels [run], and the runs it delegated when [tree] is true, or stops it when it is a job. */
    public fun cancelRun(run: RunId, tree: Boolean, by: ChatUser?): Boolean

    /** The jobs that turns at [chat], or at every chat when it is null, moved to the background and that still run. */
    public fun jobs(chat: ChatAddress?): List<JobStatus>

    /** Stops the job [job] of [chat], or of any chat when it is null, false when no such job runs there. */
    public fun stopJob(chat: ChatAddress?, job: RunId, by: ChatUser?): Boolean
}

/** A tool call that runs on in the background after its turn went on. */
@Poko
public class JobStatus @InternalAlexandriteApi constructor(
    public val id: RunId,
    /** The chat of the turn that made the call. */
    public val chat: ChatAddress,
    /** The agent of the turn that made the call. */
    public val agent: AgentId,
    public val tool: String,
    /** The call's main argument, such as its command line or path, empty when it has none. */
    public val title: String,
    /** When the call started to run. */
    public val startedAt: Instant,
    /** The principal of the turn that made the call, null when it had none. */
    public val by: ChatUser?,
)

/** A running or queued turn. */
@Poko
public class TurnStatus private constructor(
    public val turn: TurnInfo,
    public val phase: TurnPhase,
    public val queuedAt: Instant,
    /** Null while the turn is queued. */
    public val startedAt: Instant?,
) {
    init {
        require(phase != TurnPhase.QUEUED || startedAt == null) { "A queued turn has not started." }
        require(phase != TurnPhase.RUNNING || startedAt != null) { "A running turn has a start time." }
        require(phase != TurnPhase.FINISHING || startedAt != null) { "A finishing turn has a start time." }
    }

    public fun toBuilder(): Builder = Builder(turn, phase, queuedAt).startedAt(startedAt)

    public class Builder internal constructor(
        private var turn: TurnInfo,
        private var phase: TurnPhase,
        private var queuedAt: Instant,
    ) {
        private var startedAt: Instant? = null

        public fun turn(turn: TurnInfo): Builder = apply { this.turn = turn }

        public fun phase(phase: TurnPhase): Builder = apply { this.phase = phase }

        public fun queuedAt(queuedAt: Instant): Builder = apply { this.queuedAt = queuedAt }

        public fun startedAt(startedAt: Instant?): Builder = apply { this.startedAt = startedAt }

        public fun build(): TurnStatus = TurnStatus(turn, phase, queuedAt, startedAt)
    }

    public companion object {
        public fun builder(turn: TurnInfo, phase: TurnPhase, queuedAt: Instant): Builder =
            Builder(turn, phase, queuedAt)
    }
}

public inline fun TurnStatus.rebuild(block: TurnStatus.Builder.() -> Unit): TurnStatus =
    toBuilder().apply(block).build()

/** Where a turn is in its life. */
@JvmInline
@Serializable
public value class TurnPhase internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val QUEUED: TurnPhase = TurnPhase("queued")

        public val RUNNING: TurnPhase = TurnPhase("running")

        /** The turn's outcome is decided, and it delivers and stores it out of a cancel's reach. */
        public val FINISHING: TurnPhase = TurnPhase("finishing")

        /** The values this version knows. */
        public val entries: List<TurnPhase> = listOf(QUEUED, RUNNING, FINISHING)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): TurnPhase = TurnPhase(id)
    }
}
