package org.foedusprogramme.alexandrite.agent.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.turn.TurnCancelled
import org.foedusprogramme.alexandrite.agent.turn.TurnPlan
import org.foedusprogramme.alexandrite.agent.turn.TurnRunner
import org.foedusprogramme.alexandrite.agent.turn.TurnShutdown
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration

/** A job of a worker's queue. */
internal sealed interface WorkerJob {
    class Turn(val plan: TurnPlan, val ticket: AgentTicket, val capacity: Capacity) : WorkerJob

    /** A change to its key's state, such as a rotation, which runs in its place in the queue. */
    class Control(val effect: suspend () -> Unit) : WorkerJob {
        /** Completes exceptionally when [effect] failed or the agent shut down before it ran. */
        val done: CompletableDeferred<Unit> = CompletableDeferred()
    }
}

/** One FIFO worker per agent and chat key, created on demand and removed once idle, whose turns share permits. */
@Singleton
internal class TurnWorkers(
    settings: AgentSettings,
    private val lock: SubmitLock,
    private val store: ConversationStore,
    private val runner: TurnRunner,
    private val clock: Clock,
) {
    private val perKey = settings.queue.perKey
    private val permits = Semaphore(settings.maxConcurrentTurns)
    private val workers = HashMap<AgentChatKey, Worker>()

    @Volatile
    private var open = false

    val accepting: Boolean get() = open

    val keys: Set<AgentChatKey> get() = lock.locked { workers.keys.toSet() }

    fun open() {
        lock.locked { open = true }
    }

    fun close() {
        lock.locked { open = false }
    }

    /** The counted turns waiting at [key]. */
    fun waiting(key: AgentChatKey): Int = lock.locked {
        workers[key]?.queue.orEmpty().count { it is WorkerJob.Turn && it.capacity == Capacity.COUNTED }
    }

    /** Queues [turn] at [key] unless the agent is closed or the key's queue, with [alsoWaiting] turns, is full. */
    fun submit(key: AgentChatKey, turn: WorkerJob.Turn, alsoWaiting: Int = 0): Admission = lock.locked {
        when {
            !open -> Admission.Refused(RefusalReason.SHUTTING_DOWN)

            turn.capacity == Capacity.COUNTED && waiting(key) + alsoWaiting >= perKey ->
                Admission.Refused(RefusalReason.QUEUE_FULL, perKey)

            else -> {
                enqueue(key, turn)
                Admission.Accepted(turn.ticket)
            }
        }
    }

    /** Queues [job] at [key] whatever the queue holds. */
    fun enqueue(key: AgentChatKey, job: WorkerJob) {
        lock.locked {
            val worker = workers.getOrPut(key) {
                val created = Worker(key)
                created.loop = lock.launch { work(created) }
                created
            }
            worker.queue.addLast(job)
        }
    }

    /** The running and queued turns that came from [chat], or from every chat when it is null. */
    fun statuses(chat: ChatAddress?): List<TurnStatus> = lock.locked {
        workers.values.flatMap { it.statuses(chat) }.sortedBy { it.queuedAt }
    }

    /** Cancels the running turns that came from [chat], and returns whether there was one. */
    fun cancelRunning(chat: ChatAddress, cause: CancellationException): Boolean = lock.locked {
        val running = workers.values.mapNotNull { it.current }
            .filter { it.startedAt != null && it.turn?.plan?.origin == chat }
        running.forEach { it.cancel(cause) }
        running.isNotEmpty()
    }

    /** Drops [turn] while it is queued or cancels it once taken, and returns whether a worker held it. */
    fun cancel(turn: TurnId, cause: CancellationException): Boolean = lock.locked {
        for (worker in workers.values) {
            val queued = worker.queue.filterIsInstance<WorkerJob.Turn>().firstOrNull { it.plan.id == turn }
            if (queued != null) {
                worker.queue.remove(queued)
                queued.ticket.complete(notStarted(cause))
                return@locked true
            }
            val current = worker.current?.takeIf { it.turn?.plan?.id == turn }
            if (current != null) {
                current.cancel(cause)
                return@locked true
            }
        }
        false
    }

    /**
     * Ends the turns that have not started, gives the running ones [grace] to finish, then cancels them and waits
     * [cancelJoin] for them.
     */
    suspend fun drain(grace: Duration, cancelJoin: Duration) {
        val loops = lock.locked {
            open = false
            for (worker in workers.values) {
                val queued = worker.queue.filterIsInstance<WorkerJob.Turn>()
                worker.queue.removeAll(queued)
                queued.forEach { it.ticket.complete(TurnOutcome.ShutDown(replayable = true)) }
                worker.current?.takeIf { it.turn != null && it.startedAt == null }?.cancel(TurnShutdown())
            }
            workers.values.mapNotNull { it.loop }
        }
        withTimeoutOrNull(grace) { loops.joinAll() }
        val running = lock.locked {
            workers.values.mapNotNull { it.current }.filter { it.turn != null }.onEach { it.cancel(TurnShutdown()) }
        }
        withTimeoutOrNull(cancelJoin) { running.mapNotNull { it.child }.joinAll() }
        lock.locked {
            for (current in running) {
                val turn = current.turn ?: continue
                if (turn.ticket.complete(TurnOutcome.ShutDown(replayable = false))) {
                    logger.warn(
                        "Turn {} of {} did not end within {} of its cancellation: it is abandoned",
                        turn.plan.id,
                        turn.plan.key,
                        cancelJoin,
                    )
                }
            }
            for (worker in workers.values) {
                worker.queue.filterIsInstance<WorkerJob.Control>().forEach {
                    it.done.completeExceptionally(TurnShutdown())
                }
                worker.queue.clear()
            }
        }
    }

    private suspend fun work(worker: Worker) {
        val loaded = load(worker.key)
        lock.locked { worker.conversation = loaded }
        while (true) {
            val current = lock.locked { take(worker) } ?: return
            when (val job = current.job) {
                is WorkerJob.Control -> control(worker, job)
                is WorkerJob.Turn -> current.child?.join()
            }
            lock.locked { worker.current = null }
        }
    }

    /** The next job of [worker], null when it has none and is removed. */
    private fun take(worker: Worker): Current? {
        val job = worker.queue.removeFirstOrNull()
        if (job == null) {
            workers.remove(worker.key)
            return null
        }
        val current = Current(job)
        worker.current = current
        if (job is WorkerJob.Turn) current.child = launch(current, job)
        return current
    }

    private fun launch(current: Current, turn: WorkerJob.Turn): Job {
        val child = lock.launch { execute(current, turn) }
        child.invokeOnCompletion { error -> error?.let { turn.ticket.complete(interrupted(current, it)) } }
        return child
    }

    private suspend fun execute(current: Current, turn: WorkerJob.Turn) {
        val outcome = try {
            permits.withPermit {
                lock.locked {
                    current.cause?.let { throw it }
                    current.startedAt = clock.instant()
                }
                runner.run(turn.plan)
            }
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                failed(turn, e)
            } else {
                turn.ticket.complete(interrupted(current, e))
                throw e
            }
        } catch (e: Exception) {
            failed(turn, e)
        }
        turn.ticket.complete(outcome)
    }

    private fun failed(turn: WorkerJob.Turn, error: Exception): TurnOutcome {
        logger.error("Turn {} of {} failed", turn.plan.id, turn.plan.key, error)
        return TurnOutcome.Failed(error.toString())
    }

    /** How a turn whose coroutine ended with [error] ends. */
    private fun interrupted(current: Current, error: Throwable): TurnOutcome = lock.locked {
        when {
            error !is CancellationException -> TurnOutcome.Failed(error.toString())
            current.cause is TurnCancelled -> TurnOutcome.Cancelled
            else -> TurnOutcome.ShutDown(replayable = current.startedAt == null)
        }
    }

    private suspend fun control(worker: Worker, job: WorkerJob.Control) {
        try {
            job.effect()
            job.done.complete(Unit)
        } catch (e: CancellationException) {
            job.done.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            job.done.completeExceptionally(e)
        }
        val loaded = load(worker.key)
        lock.locked { worker.conversation = loaded }
    }

    private suspend fun load(key: AgentChatKey): ConversationId? = try {
        store.current(key).id
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Cannot read the current conversation of {}: {}", key, e.toString())
        null
    }
}

private class Worker(val key: AgentChatKey) {
    val queue = ArrayDeque<WorkerJob>()
    var current: Current? = null
    var loop: Job? = null

    /** The conversation the worker last read as current, null before then. */
    var conversation: ConversationId? = null

    fun statuses(chat: ChatAddress?): List<TurnStatus> {
        val conversation = conversation ?: return emptyList()
        val running = current?.let { current -> current.turn?.let { it to current.startedAt } }
        val queued = queue.filterIsInstance<WorkerJob.Turn>().map { it to null }
        return (listOfNotNull(running) + queued)
            .filter { (turn, _) -> chat == null || turn.plan.origin == chat }
            .map { (turn, startedAt) -> status(turn.plan, conversation, startedAt) }
    }
}

/** The job a worker took from its queue. */
private class Current(val job: WorkerJob) {
    var child: Job? = null
    var startedAt: Instant? = null
    var cause: CancellationException? = null

    val turn: WorkerJob.Turn? get() = job as? WorkerJob.Turn

    fun cancel(cause: CancellationException) {
        if (this.cause == null) this.cause = cause
        child?.cancel(cause)
    }
}

private fun status(plan: TurnPlan, conversation: ConversationId, startedAt: Instant?): TurnStatus {
    val turn = TurnInfo.builder(plan.id, plan.origin, conversation, plan.kind)
        .actor(plan.actor)
        .agent(plan.key.agent)
        .replyTarget(plan.replyTarget)
        .build()
    val phase = if (startedAt == null) TurnPhase.QUEUED else TurnPhase.RUNNING
    return TurnStatus.builder(turn, phase, plan.queuedAt).startedAt(startedAt).build()
}

private val logger = LoggerFactory.getLogger(TurnWorkers::class.java)
