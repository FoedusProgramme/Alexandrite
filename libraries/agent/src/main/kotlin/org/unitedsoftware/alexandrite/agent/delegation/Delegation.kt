package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import org.unitedsoftware.alexandrite.agent.job.EndedJob
import org.unitedsoftware.alexandrite.agent.job.JobInbox
import org.unitedsoftware.alexandrite.agent.job.Jobs
import org.unitedsoftware.alexandrite.agent.job.jobPlan
import org.unitedsoftware.alexandrite.agent.permission.TurnTaint
import org.unitedsoftware.alexandrite.agent.tool.DelegatingTurn
import org.unitedsoftware.alexandrite.agent.tool.RunChain
import org.unitedsoftware.alexandrite.agent.turn.FollowUp
import org.unitedsoftware.alexandrite.agent.turn.FollowUps
import org.unitedsoftware.alexandrite.agent.turn.TurnPermit
import org.unitedsoftware.alexandrite.agent.turn.TurnSource
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnLineage
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo
import java.time.Clock
import java.time.Instant

/** The name of the tool that delegates a task to a sub-agent. */
internal const val DELEGATE_TOOL: String = "agent.delegate"

/** How deep a tree of sub-agent runs may grow below its root turn. */
internal const val MAX_DEPTH: Int = 3

/** A sub-agent run of [profile] on [task] in [conversation], which a call of [parent]'s turn started as [run]. */
internal class Delegation(
    val run: RunId,
    val lineage: TurnLineage,
    val conversation: ConversationInfo,
    val profile: AgentProfile,
    val task: String,
    val parent: DelegatingTurn,
    val chain: RunChain,
    val background: Boolean = false,
    /** Why the run is tainted by the turn that delegated it, null when that turn is clean. */
    val inherited: String? = null,
    /** Why the run is tainted by the earlier run it goes on from, null when there is none or it is clean. */
    val resumed: String? = null,
    /** Hands each untrusted content the run takes in to the call that delegated it, as it comes. */
    private val report: (String) -> Unit = {},
) {
    /** The run's turn, null until it started. */
    @Volatile
    var turn: TurnInfo? = null
        private set

    /** The taint of the run's turn, null until it started. */
    @Volatile
    var taint: TurnTaint? = null
        private set

    /** Counts the run's marks in [taint], the taint of its turn, also when they could not be stored. */
    fun opened(taint: TurnTaint) {
        inherited?.let(taint::carry)
        resumed?.let(taint::note)
    }

    /** Notes that the run's turn [turn] started with [taint], whose untrusted content goes to the delegating call. */
    fun started(turn: TurnInfo, taint: TurnTaint) {
        this.taint = taint
        this.turn = turn
        if (taint.unread) report("its mark could not be read")
        taint.onNoted(report)
    }
}

/**
 * The follow-ups of a sub-agent run, which takes no messages but the reports of the background runs it started, in
 * [conversation], its own.
 */
internal class RunInbox(private val conversation: ConversationId, private val jobs: Jobs, private val clock: Clock) :
    FollowUps,
    JobInbox {
    private val lock = Any()
    private val reports = ArrayDeque<FollowUp>()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    override fun offer(job: EndedJob): Boolean {
        synchronized(lock) {
            if (closed) return false
            reports += FollowUp(jobPlan(job, clock.instant())) {}
        }
        changed.trySend(Unit)
        return true
    }

    override fun ended() {
        changed.trySend(Unit)
    }

    /** Takes no more reports, and returns those the run did not take. */
    fun close(): List<EndedJob> = synchronized(lock) {
        closed = true
        reports.map { (it.plan.input as TurnSource.FromJob).job }.also { reports.clear() }
    }

    override fun take(principal: UserAddress, chat: ChatAddress, since: Instant): List<FollowUp> = emptyList()

    override fun restore(followUps: List<FollowUp>) {
        synchronized(lock) { followUps.asReversed().forEach(reports::addFirst) }
    }

    override fun queued(principal: UserAddress, chat: ChatAddress, since: Instant): Boolean = false

    override suspend fun await(principal: UserAddress, chat: ChatAddress, since: Instant) = awaitCancellation()

    override fun takeJobs(chat: ChatAddress, conversation: ConversationId): List<FollowUp> {
        if (conversation != this.conversation) return emptyList()
        return synchronized(lock) { reports.toList().also { reports.clear() } }
    }

    override suspend fun awaitJobs(conversation: ConversationId): Boolean {
        if (conversation != this.conversation) return false
        while (true) {
            if (synchronized(lock) { reports.isNotEmpty() }) return true
            if (!jobs.running(conversation)) return synchronized(lock) { reports.isNotEmpty() }
            changed.receive()
        }
    }
}

/** A permit of the pool of background runs, which its run gives up while it waits for runs it started. */
internal class PoolPermit(private val pool: Semaphore) : TurnPermit {
    private var held = false

    suspend fun acquire() {
        pool.acquire()
        held = true
    }

    fun release() {
        if (!held) return
        held = false
        pool.release()
    }

    override suspend fun <T> released(block: suspend () -> T): T {
        release()
        val result = block()
        acquire()
        return result
    }
}

/** The permit of a sub-agent run, which runs on the permit of its root turn. */
internal object RootPermit : TurnPermit {
    override suspend fun <T> released(block: suspend () -> T): T = block()
}
