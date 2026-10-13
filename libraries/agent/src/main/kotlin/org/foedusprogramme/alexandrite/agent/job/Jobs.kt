package org.foedusprogramme.alexandrite.agent.job

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.cancel.TurnShutdown
import org.foedusprogramme.alexandrite.agent.config.AgentConfig
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.tool.AgentToolContext
import org.foedusprogramme.alexandrite.agent.tool.CANCELLED_RESULT
import org.foedusprogramme.alexandrite.agent.tool.failedResult
import org.foedusprogramme.alexandrite.agent.tool.shaped
import org.foedusprogramme.alexandrite.agent.tool.textResult
import org.foedusprogramme.alexandrite.agent.tool.timedOutResult
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.turn.JobStatus
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
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
)

/** A [job] that ended with [result], which is unstored and shaped like the result of a call in the foreground. */
internal class EndedJob(
    val job: BackgroundJob,
    val result: ToolResultEntry,
    /** The paths that the call reported as touched. */
    val touched: List<Path>,
    /** Why the turn that takes the result counts as tainted, null when it need not. */
    val untrusted: String?,
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
)

/** Hands the result of each job that ended to a turn of its conversation. */
internal fun interface JobDelivery {
    /** False when the agent takes no turns any more. */
    fun deliver(job: EndedJob): Boolean
}

/** The tool calls that run on in the background in a scope of the agent, at most `maxJobsPerChat` per chat. */
@Singleton
internal class Jobs(
    private val settings: AgentSettings,
    private val records: JobRecords,
    private val delivery: JobDelivery,
    private val clock: Clock,
    scope: PluginScope,
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Any()
    private val active = LinkedHashMap<RunId, Active>()
    private var open = true
    private val defaults = AgentConfig()

    /** How long a job stopped at a shutdown may take to end before it is abandoned. */
    private val shutdownWait = minOf(ABANDON_AFTER, settings.shutdown.cancelJoinSeconds.seconds / 2)

    /** How long a call of [agent] runs before it moves to the background. */
    fun foreground(agent: AgentId): Duration =
        (settings.agents[agent.value]?.foregroundSeconds ?: defaults.foregroundSeconds).seconds

    /** Moves the call of [detaching] to the background and returns its job, null when it stays in the foreground. */
    suspend fun detach(detaching: Detaching): RunId? {
        val left = detaching.limit - detaching.ran
        if (!left.isPositive()) return null
        val turn = detaching.turn
        val call = detaching.call
        val started = clock.instant().minus(detaching.ran.toJavaDuration())
        val job = synchronized(lock) {
            val running = active.values.count { it.job.turn.chat == turn.chat }
            if (!open || running >= settings.maxJobsPerChat) return null.also { kept(turn, call, running) }
            val background =
                BackgroundJob(newJobId(), turn, call, callTitle(detaching.arguments), started, detaching.limit)
            val job = Active(background, detaching.running, detaching.context)
            active[background.id] = job
            job.watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) { watch(job, left) }
            job
        }
        try {
            withContext(NonCancellable) { records.put(job.job) }
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
        return job.job.id
    }

    /** The jobs of [chat] that run and are not being stopped, oldest first. */
    fun list(chat: ChatAddress): List<JobStatus> = synchronized(lock) {
        active.values.filter { it.job.turn.chat == chat && it.cause == null }.map { it.status }
    }.sortedBy { it.startedAt }

    /** Stops the job [id] for [by], when [chat] runs it or [chat] is null, and returns whether it ran. */
    fun stop(id: RunId, chat: ChatAddress?, by: ChatUser?): Boolean {
        val stopped = synchronized(lock) {
            active[id]?.takeIf { (chat == null || it.job.turn.chat == chat) && it.stop(TurnCancelled(by)) }
        } ?: return false
        logger.info("Job {} ({}) of {} is stopped by {}", id, described(stopped.job), stopped.job.turn.chat, who(by))
        return true
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
            job.context.close()
            synchronized(lock) { active.remove(job.job.id) }
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

    private suspend fun ended(job: Active, result: ToolResultEntry) {
        val taint = job.context.taint
        taint.record()
        val untrusted = if (taint.tainted) {
            taint.known?.reason ?: "job ${job.job.id} ran for a turn that counted as tainted"
        } else {
            null
        }
        val ended = EndedJob(job.job, result, job.context.touchedPaths, untrusted)
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
            job.tool.cancel()
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

    private fun kept(turn: TurnInfo, call: ToolCallPart, running: Int) {
        if (!open) return
        logger.info(
            "Call {} of {} in turn {} stays in the foreground: chat {} runs {} jobs already",
            call.id,
            call.name,
            turn.id,
            turn.chat,
            running,
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
private class Active(val job: BackgroundJob, val tool: Deferred<ToolResult>, val context: AgentToolContext) {
    lateinit var watcher: Job

    /** Completes once the job's record is written, or failed to be. */
    val recorded = CompletableDeferred<Unit>()

    /** Why the job is being stopped, null while it runs on. */
    var cause: CancellationException? = null

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

private fun who(by: ChatUser?): String = by?.address?.toString() ?: "the host"

/** How long a stopped tool may take to end before it is abandoned. */
private val ABANDON_AFTER = 5.seconds

private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

private const val JOB_ID_LENGTH = 6

private val logger = LoggerFactory.getLogger(Jobs::class.java)
