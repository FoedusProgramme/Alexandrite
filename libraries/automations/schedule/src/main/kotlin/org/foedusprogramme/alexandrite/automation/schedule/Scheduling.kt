package org.foedusprogramme.alexandrite.automation.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.automation.common.Fired
import org.foedusprogramme.alexandrite.automation.common.Occurrence
import org.foedusprogramme.alexandrite.automation.common.Scheduler
import org.foedusprogramme.alexandrite.automation.common.SchedulerEvents
import org.foedusprogramme.alexandrite.automation.common.Task
import org.foedusprogramme.alexandrite.automation.common.TimeSource
import org.foedusprogramme.alexandrite.automation.common.Trigger
import org.foedusprogramme.alexandrite.automation.common.When
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.ReplyRoute
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiator
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnTicket
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the schedule of [tasks] from the open until the stop, starting it again with a growing pause when it fails,
 * and fires each occurrence as a reminder turn in its task's chat.
 */
@Singleton
internal class Scheduling(
    private val config: ScheduleConfig,
    private val tasks: TaskRecords,
    private val initiator: TurnInitiator,
    private val notices: Notices,
    private val scope: PluginScope,
    time: TimeSource?,
) : Lifecycle {
    val time: TimeSource = time ?: TimeSource.system

    private val closing = MutableStateFlow(false)
    private val trigger = Trigger<String> { task, occurrence -> fire(task, occurrence) }
    private val events = Events()

    @Volatile
    private var current = newScheduler()

    @Volatile
    private var job: Job? = null

    /** The scheduler that runs now, or that ran last while it waits to start again. */
    fun scheduler(): Scheduler<String> = current

    override suspend fun onOpen() {
        job = scope.launch { supervise() }
    }

    override suspend fun onClose() {
        closing.value = true
    }

    override fun onStop() {
        job?.cancel()
    }

    private fun newScheduler(): Scheduler<String> =
        Scheduler(tasks, trigger, events, time, config.missedWindowHours.hours)

    private suspend fun supervise() {
        var pause = FIRST_PAUSE
        while (true) {
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            try {
                current.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("The schedule stopped, and starts again in {}", pause, e)
            }
            if (started.elapsedNow() > STEADY) pause = FIRST_PAUSE
            delay(pause)
            pause = (pause * 2).coerceAtMost(LAST_PAUSE)
            current = newScheduler()
        }
    }

    private suspend fun fire(task: Task<String>, occurrence: Occurrence): Fired {
        val record = tasks.find(task.id)
        if (record == null) {
            logger.warn("Scheduled task {} came due without its record: skipped", task.id)
            return Fired.DONE
        }
        val turn = InitiatedTurn.builder(record.key, TurnKind.REMINDER, framing(task.id, record, occurrence))
            .route(ReplyRoute.ToChat(record.chat))
            .trust(if (record.mandate == null) Trust.UNTRUSTED else Trust.TRUSTED)
            .mandate(record.mandate)
            .build()
        logger.info("Scheduled task {} of {} fires, due {}", task.id, record.key, occurrence.due)
        var tries = 0
        while (true) {
            when (val admission = initiator.initiate(turn)) {
                is Admission.Accepted -> return fired(outcome(admission.ticket))

                is Admission.Refused -> {
                    if (admission.reason != RefusalReason.SHUTTING_DOWN) {
                        refused(task.id, record, occurrence, admission.reason)
                        return Fired.DONE
                    }
                    if (closing.value || ++tries > OPEN_TRIES) return Fired.REPLAY
                    withTimeoutOrNull(1.seconds) { closing.first { it } }
                }
            }
        }
    }

    /** The outcome of [ticket], also when the stop cancels the wait just after the agent ended its turn. */
    private suspend fun outcome(ticket: TurnTicket): TurnOutcome = try {
        ticket.outcome()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { withTimeoutOrNull(SETTLE) { ticket.outcome() } } ?: throw e
    }

    private fun fired(outcome: TurnOutcome): Fired = when {
        outcome !is TurnOutcome.ShutDown -> Fired.DONE
        outcome.replayable -> Fired.REPLAY
        else -> Fired.CUT_OFF
    }

    private fun refused(id: String, record: TaskRecord, occurrence: Occurrence, reason: RefusalReason) {
        logger.warn("Scheduled task {} of {} was refused ({}): not run", id, record.key, reason)
        notices.send(record.chat) {
            it.text(
                TextKeys.NOTICE_REFUSED,
                "id" to id,
                "due" to shown(occurrence.due, it.zone),
                "reason" to reason,
                "prompt" to brief(record.prompt),
            )
        }
    }

    private inner class Events : SchedulerEvents<String> {
        override fun cutOff(task: Task<String>, occurrence: Occurrence, crashed: Boolean) {
            val record = tasks.find(task.id) ?: return
            logger.warn("Scheduled task {} of {} was cut off (crashed: {})", task.id, record.key, crashed)
            notices.send(record.chat) {
                it.text(TextKeys.NOTICE_CUT_OFF, "id" to task.id, "prompt" to brief(record.prompt))
            }
        }

        override fun missed(task: Task<String>, due: Instant) {
            val record = tasks.find(task.id) ?: return
            logger.warn("Scheduled task {} of {} missed its run due {}", task.id, record.key, due)
            val key = if (task.schedule is When.Once) TextKeys.NOTICE_MISSED_ONCE else TextKeys.NOTICE_MISSED_RECURRING
            notices.send(record.chat) {
                it.text(key, "id" to task.id, "due" to shown(due, it.zone), "prompt" to brief(record.prompt))
            }
        }

        override fun skipped(task: Task<String>, due: Instant) {
            logger.info("Scheduled task {} skips its run due {}: the previous one still runs", task.id, due)
        }

        override fun failed(task: Task<String>, occurrence: Occurrence, error: Exception) {
            logger.warn("Scheduled task {} failed to fire, due {}", task.id, occurrence.due, error)
        }
    }

    private companion object {
        val FIRST_PAUSE = 1.seconds
        val LAST_PAUSE = 5.minutes
        val STEADY = 10.minutes
        val SETTLE = 1.seconds

        /** How many seconds a task waits for the agent to open. */
        const val OPEN_TRIES = 120
    }
}

/** The input of the turn of task [id] for [occurrence]. */
internal fun framing(id: String, record: TaskRecord, occurrence: Occurrence): String {
    val zone = record.zoneId
    val late = occurrence.late.inWholeMinutes.takeIf { it > 0 }?.let { ", running $it min late" }.orEmpty()
    return "Scheduled task $id, set by ${record.byName} on ${day(record.createdInstant, zone)}; " +
        "due ${shown(occurrence.due, zone)}$late. Do what it asks; it was scheduled earlier, so do not schedule it " +
        "again.\n\n${record.prompt}"
}

/** [prompt] cut to a line people read at a glance. */
internal fun brief(prompt: String): String {
    val line = prompt.trim().replace(Regex("\\s+"), " ")
    return if (line.length <= BRIEF) line else line.take(BRIEF - 1).trimEnd() + "…"
}

private const val BRIEF = 80

private val logger = LoggerFactory.getLogger(Scheduling::class.java)
