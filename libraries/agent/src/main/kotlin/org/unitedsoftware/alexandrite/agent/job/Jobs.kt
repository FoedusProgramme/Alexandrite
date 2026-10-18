package org.unitedsoftware.alexandrite.agent.job

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.cancel.TurnShutdown
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.config.DEFAULT_FOREGROUND_SECONDS
import org.unitedsoftware.alexandrite.agent.delegation.DELEGATE_TOOL
import org.unitedsoftware.alexandrite.agent.tool.AgentToolContext
import org.unitedsoftware.alexandrite.agent.tool.CANCELLED_RESULT
import org.unitedsoftware.alexandrite.agent.tool.failedResult
import org.unitedsoftware.alexandrite.agent.tool.shaped
import org.unitedsoftware.alexandrite.agent.tool.textResult
import org.unitedsoftware.alexandrite.agent.tool.timedOutResult
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.hook.Hooks
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.JobLifecycle
import org.unitedsoftware.alexandrite.sdk.turn.JobStatus
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** A tool call of [turn] that runs on in the background as [id], for at most [limit] since [startedAt]. */
internal class BackgroundJob(
    val id: RunId,
    val turn: TurnInfo,
    val call: ToolCallPart,
    /** The call's main argument, empty when it has none. */
    val title: String,
    val startedAt: Instant,
    val limit: Duration,
    /** What the job carries from the turn that started it to the turns that take its results. */
    val carried: CoroutineContext = EmptyCoroutineContext,
    val ancestors: Set<RunId> = emptySet(),
    val resultReplyTarget: ReplyTarget = turn.replyTarget,
    /** The conversation of the sub-agent run that the job is. */
    val delegated: ConversationId? = null,
) {
    /** The conversation of the turn that started the job, which takes its result: a run's own, inside a run. */
    val resultConversation get() = turn.conversation
    val resultTurn get() = turn.id
}

/** A [job] that ended with [result], which is unstored and shaped like the result of a call in the foreground. */
internal class EndedJob(
    val job: BackgroundJob,
    val result: ToolResultEntry,
    /** The paths that the call reported as touched. */
    val touched: List<Path>,
    /** Why the turn that takes the result counts as tainted, null when it need not. */
    val untrusted: String?,
    val final: Boolean = true,
    val cancelled: () -> Boolean = { false },
    val consumed: () -> Unit = {},
) {
    /** The text parts of [result], one after the other. */
    val text: String get() = result.content.filterIsInstance<TextPart>().joinToString("\n") { it.text }
}

/** A call of [turn] whose tool has run as [running] for [ran] of its [limit]. */
internal class Detaching(
    val turn: TurnInfo,
    val call: ToolCallPart,
    val arguments: JsonObject,
    val running: Deferred<ToolResult>,
    val context: AgentToolContext,
    val ran: Duration,
    val limit: Duration,
    val id: RunId? = null,
    val cleanup: suspend () -> Unit = {},
    val delegated: ConversationId? = null,
)

/** A part of a turn's coroutine context that the turn's jobs carry on, to the turns that take their results. */
internal interface JobCarried : CoroutineContext.Element

/** What became of a call asked to move to the background. */
internal sealed interface Detached {
    class Started(val id: RunId) : Detached

    class Kept(val reason: NotRunReason, val why: String) : Detached
}

/** Hands the result of each job that ended to a turn of its conversation. */
internal fun interface JobDelivery {
    /** False when the agent takes no turns any more. */
    fun deliver(job: EndedJob): Boolean

    /** Tells the turn waiting in [job]'s conversation, if one is, that the job is gone, with or without a result. */
    fun ended(job: BackgroundJob) {}
}

/** The tool calls that run on in the background in a scope of the agent, at most `maxJobsPerChat` per chat. */
@Singleton
internal class Jobs(
    private val settings: AgentSettings,
    private val records: JobRecords,
    private val delivery: JobDelivery,
    private val clock: Clock,
    private val hooks: Hooks,
    scope: PluginScope,
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Any()
    private val active = LinkedHashMap<RunId, Active>()
    private var open = true

    /** How long a job stopped at a shutdown may take to end before it is abandoned. */
    private val shutdownWait = minOf(ABANDON_AFTER, settings.shutdown.cancelJoinSeconds.seconds / 2)

    /** How long a call of [agent] runs before it moves to the background. */
    fun foreground(agent: AgentId): Duration =
        (settings.agents[agent.value]?.foregroundSeconds ?: DEFAULT_FOREGROUND_SECONDS).seconds

    /** Moves the call of [detaching] to the background, or says why it stays in the foreground. */
    suspend fun detach(detaching: Detaching): Detached {
        val left = detaching.limit - detaching.ran
        if (!left.isPositive()) return Detached.Kept(NotRunReason.CANCELLED, "the call has no time left to run")
        val turn = detaching.turn
        val call = detaching.call
        val started = clock.instant().minus(detaching.ran.toJavaDuration())
        currentCoroutineContext().ensureActive()
        val parentJob = currentCoroutineContext()[Job]
        val carried = currentCoroutineContext().fold<CoroutineContext>(EmptyCoroutineContext) { kept, element ->
            if (element is JobCarried) kept + element else kept
        }
        val ancestors = detaching.context.delegating?.runAncestors ?: turn.lineage?.run?.let(::setOf).orEmpty()
        val job = synchronized(lock) {
            val refusal = refusal(turn, call, parentJob, ancestors)
            if (refusal != null) return refusal.also { kept(turn, call, it) }
            val background =
                BackgroundJob(
                    detaching.id ?: newJobId(),
                    turn,
                    call,
                    callTitle(detaching.arguments),
                    started,
                    detaching.limit,
                    carried,
                    ancestors,
                    if (detaching.context.delegating?.activityVisible == true) ReplyTarget.CHAT else turn.replyTarget,
                    detaching.delegated,
                )
            val job = Active(background, detaching.running, detaching.context, detaching.cleanup)
            val running = active[background.id]
            if (running != null) return Detached.Kept(NotRunReason.CANCELLED, "job ${running.job.id} runs already")
            active[background.id] = job
            job.watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) { watch(job, left) }
            job
        }
        try {
            withContext(NonCancellable) {
                records.put(job.job)
                hooks.fire(TurnPoints.JOB_STARTED, payload(job.job))
                if (detaching.ran.isPositive()) hooks.fire(TurnPoints.TOOL_BACKGROUNDED, payload(job.job))
                job.context.background { update -> update(job, update) }
            }
        } finally {
            job.recorded.complete(Unit)
        }
        logger.info(
            "Call {} of {} in turn {} of {} runs on in the background as job {}",
            call.id,
            call.name,
            turn.id,
            turn.key,
            job.job.id,
        )
        return Detached.Started(job.job.id)
    }

    /** Why the call of [turn] may not move to the background now; called under [lock]. */
    private fun refusal(turn: TurnInfo, call: ToolCallPart, parentJob: Job?, ancestors: Set<RunId>): Detached.Kept? {
        if (!open) return Detached.Kept(NotRunReason.INTERRUPTED, "the agent is stopping")
        if (parentJob?.isActive == false || ancestors.any { active[it]?.cause != null }) {
            return Detached.Kept(NotRunReason.CANCELLED, "the turn that made the call is being stopped")
        }
        val chat = active.values.filter { it.job.turn.chat == turn.chat }
        if (call.name == DELEGATE_TOOL) {
            val runs = chat.count { it.job.call.name == DELEGATE_TOOL }
            if (runs >= settings.maxBackgroundRunsPerChat) {
                return Detached.Kept(
                    NotRunReason.BACKGROUND_FULL,
                    "the chat already runs $runs background sub-agent runs",
                )
            }
        }
        if (chat.size >= settings.maxJobsPerChat) {
            return Detached.Kept(NotRunReason.BACKGROUND_FULL, "the chat already runs ${chat.size} background jobs")
        }
        return null
    }

    suspend fun start(
        turn: TurnInfo,
        call: ToolCallPart,
        arguments: JsonObject,
        context: AgentToolContext,
        limit: Duration,
        id: RunId? = null,
        cleanup: suspend () -> Unit = {},
        delegated: ConversationId? = null,
        block: suspend () -> ToolResult,
    ): Detached {
        val running = scope.async(start = CoroutineStart.LAZY) { block() }
        return try {
            detach(Detaching(turn, call, arguments, running, context, Duration.ZERO, limit, id, cleanup, delegated))
                .also { if (it is Detached.Kept) running.cancel() }
        } catch (e: Throwable) {
            running.cancel()
            throw e
        }
    }

    /** The jobs of [chat] that run and are not being stopped, oldest first. */
    fun list(chat: ChatAddress): List<JobStatus> = synchronized(lock) {
        active.values.filter { it.job.turn.chat == chat && it.cause == null }.map { it.status }
    }.sortedBy { it.startedAt }

    /** Stops the job [id] for [by], when [chat] runs it or [chat] is null, and returns whether it ran. */
    fun stop(id: RunId, chat: ChatAddress?, by: ChatUser?, tree: Boolean = true): Boolean {
        val stopped = synchronized(lock) {
            active.values.filter {
                (it.job.id == id || (tree && id in it.job.ancestors)) &&
                    (chat == null || it.job.turn.chat == chat)
            }.filter { it.stop(TurnCancelled(by)) }
        }
        stopped.forEach {
            logger.info("Job {} ({}) of {} is stopped by {}", it.job.id, described(it.job), it.job.turn.chat, who(by))
        }
        return stopped.isNotEmpty()
    }

    /** Stops the jobs of [chat] for [by] and returns how many ran. */
    fun stopAll(chat: ChatAddress, by: ChatUser?): Int {
        val stopped = synchronized(lock) {
            active.values.filter { it.job.turn.chat == chat && it.stop(TurnCancelled(by)) }
        }
        if (stopped.isNotEmpty()) {
            logger.info("The jobs {} of {} are stopped by {}", stopped.joinToString { "${it.job.id}" }, chat, who(by))
        }
        return stopped.size
    }

    /** Forgets the record of [job], whose result is stored or dropped. */
    suspend fun delivered(job: BackgroundJob) {
        records.remove(job.id)
    }

    /** Whether a job whose result [conversation] takes still runs. */
    fun running(conversation: ConversationId): Boolean =
        synchronized(lock) { active.values.any { it.job.resultConversation == conversation } }

    /** Stops the jobs that the run [run] or a run below it started, as [run] ends, and returns how many ran. */
    fun stopUnder(run: RunId): Int {
        val stopped = synchronized(lock) {
            active.values.filter { run in it.job.ancestors && it.job.id != run && it.stop(TurnCancelled(null)) }
        }
        if (stopped.isNotEmpty()) {
            logger.info("The jobs {} are stopped as run {} ends", stopped.joinToString { "${it.job.id}" }, run)
        }
        return stopped.size
    }

    /** Stops every job as the agent shuts down, keeping their records, and waits at most [wait] for them to end. */
    suspend fun drain(wait: Duration) {
        val watchers = synchronized(lock) {
            open = false
            active.values.onEach { it.stop(TurnShutdown()) }.map { it.watcher }
        }
        withTimeoutOrNull(wait) { watchers.joinAll() }
    }

    /** Awaits the tool of [job] for at most [left], then hands its result on once the job is recorded. */
    private suspend fun watch(job: Active, left: Duration) {
        try {
            val result = try {
                result(job, left)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { stopped(job) }
                throw e
            }
            job.recorded.await()
            ended(job, result)
        } finally {
            withContext(NonCancellable) {
                job.cleanup()
                hooks.fire(TurnPoints.JOB_ENDED, payload(job.job))
            }
            job.context.close()
            synchronized(lock) { active.remove(job.job.id) }
            delivery.ended(job.job)
        }
    }

    /** How the tool of [job] ends within [left]. */
    private suspend fun result(job: Active, left: Duration): ToolResultEntry {
        val call = job.job.call
        val returned = try {
            withTimeoutOrNull(left) { job.tool.await() }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            logger.warn("Job {} ({}) failed: {}", job.job.id, described(job.job), e.toString())
            return failedResult(call, e)
        }
        if (returned == null) {
            logger.warn("Job {} ({}) timed out after {}", job.job.id, described(job.job), job.job.limit)
            stop(job, ABANDON_AFTER)
            return timedOutResult(call, job.job.limit)
        }
        val outcome = if (returned.isError) ToolOutcome.Failed else ToolOutcome.Succeeded
        return ToolResultEntry(null, call.id, call.name, shaped(returned.content), outcome)
    }

    /** Stops the tool of [job], forgetting its record unless the agent shuts down. */
    private suspend fun stopped(job: Active) {
        val shutdown = synchronized(lock) { job.cause } !is TurnCancelled
        stop(job, if (shutdown) shutdownWait else ABANDON_AFTER)
        job.recorded.await()
        if (!shutdown) records.remove(job.job.id)
    }

    /** Hands [update] of [job] to a turn, and returns false when the previous one is not taken yet or the job stops. */
    private suspend fun update(job: Active, update: ToolResult): Boolean {
        job.recorded.await()
        currentCoroutineContext().ensureActive()
        val admit = synchronized(lock) {
            (job.cause == null && !job.pendingUpdate).also { if (it) job.pendingUpdate = true }
        }
        if (!admit) return false
        val outcome = if (update.isError) ToolOutcome.Failed else ToolOutcome.Succeeded
        val result = ToolResultEntry(null, job.job.call.id, job.job.call.name, shaped(update.content), outcome)
        val taint = job.context.taint
        val reason = if (taint.tainted) taint.known?.reason ?: "untrusted monitor output" else null
        val ended = EndedJob(
            job.job,
            result,
            job.context.touchedPaths,
            reason,
            final = false,
            cancelled = { synchronized(lock) { job.cause != null } },
            consumed = { synchronized(lock) { job.pendingUpdate = false } },
        )
        if (delivery.deliver(ended)) return true
        ended.consumed()
        return false
    }

    private suspend fun ended(job: Active, result: ToolResultEntry) {
        val taint = job.context.taint
        taint.record()
        val untrusted = if (taint.tainted) {
            taint.known?.reason ?: "job ${job.job.id} ran for a turn that counted as tainted"
        } else {
            null
        }
        val ended = EndedJob(
            job.job,
            result,
            job.context.touchedPaths,
            untrusted,
            cancelled = { synchronized(lock) { job.cause != null } },
        )
        logger.info("Job {} ({}) of {} ended: {}", job.job.id, described(job.job), job.job.turn.chat, result.outcome)
        if (!delivery.deliver(ended)) {
            logger.warn(
                "Job {} ({}) ended while the agent stops: its result is not delivered",
                job.job.id,
                described(job.job),
            )
        }
    }

    /** Cancels the tool of [job] and waits at most [wait] for it to end. */
    private suspend fun stop(job: Active, wait: Duration) {
        withContext(NonCancellable) {
            job.tool.cancel(synchronized(lock) { job.cause } ?: TurnCancelled(null))
            if (withTimeoutOrNull(wait) { job.tool.join() } == null) {
                logger.warn(
                    "Job {} ({}) still runs {} after it was stopped: it is abandoned and its result discarded",
                    job.job.id,
                    described(job.job),
                    wait,
                )
            }
        }
    }

    private fun kept(turn: TurnInfo, call: ToolCallPart, kept: Detached.Kept) {
        if (!open) return
        logger.info(
            "Call {} of {} in turn {} of {} stays in the foreground: {}",
            call.id,
            call.name,
            turn.id,
            turn.chat,
            kept.why,
        )
    }

    /** A new job id, `j-` and six random letters and digits not used by a running job. */
    private fun newJobId(): RunId {
        val random = ThreadLocalRandom.current()
        while (true) {
            val chars = CharArray(JOB_ID_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
            val id = RunId("j-${String(chars)}")
            if (id !in active) return id
        }
    }
}

/** A job and what runs it: its tool as [tool] and the [watcher] that awaits it. */
private class Active(
    val job: BackgroundJob,
    val tool: Deferred<ToolResult>,
    val context: AgentToolContext,
    val cleanup: suspend () -> Unit,
) {
    lateinit var watcher: Job

    /** Completes once the job's record is written, or failed to be. */
    val recorded = CompletableDeferred<Unit>()

    /** Why the job is being stopped, null while it runs on. */
    var cause: CancellationException? = null

    /** Whether an update of the job waits for a turn to take it. */
    var pendingUpdate: Boolean = false

    val status: JobStatus
        get() = JobStatus(job.id, job.turn.chat, job.call.name, job.title, job.startedAt, job.turn.actor)

    /** Stops the job with [cause] unless it is being stopped, and returns whether it was not. */
    fun stop(cause: CancellationException): Boolean {
        if (this.cause != null) return false
        this.cause = cause
        watcher.cancel(cause)
        return true
    }
}

/** How long a stopped tool may take to end before it is abandoned. */
private val ABANDON_AFTER = 5.seconds

private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

private const val JOB_ID_LENGTH = 6

private val logger = LoggerFactory.getLogger(Jobs::class.java)

private fun payload(job: BackgroundJob): JobLifecycle = JobLifecycle(job.turn, job.id, job.call)
