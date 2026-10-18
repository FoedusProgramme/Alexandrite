package org.unitedsoftware.alexandrite.automation.heartbeat

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.automation.common.BeatEvents
import org.unitedsoftware.alexandrite.automation.common.BeatSkip
import org.unitedsoftware.alexandrite.automation.common.Beater
import org.unitedsoftware.alexandrite.automation.common.Beats
import org.unitedsoftware.alexandrite.automation.common.TimeSource
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Beats the configured heartbeats while the plugin is open, each beat a heartbeat turn of its agent. */
@Singleton
internal class HeartbeatRunner(
    private val heartbeats: Heartbeats,
    private val initiator: TurnInitiator,
    private val scope: PluginScope,
    time: TimeSource?,
) : Lifecycle,
    BeatEvents<AgentId> {
    private val time = time ?: TimeSource.system

    /** The refusals logged per agent since its last admitted beat. */
    private val refusals = ConcurrentHashMap<AgentId, MutableSet<RefusalReason>>()

    @Volatile
    private var started: Instant? = null
    private var loop: Job? = null

    override suspend fun onOpen() {
        if (heartbeats.all.isEmpty()) {
            logger.debug("No agent has a heartbeat")
            return
        }
        for (heartbeat in heartbeats.all.filter { heartbeats.homeless(it.agent) }) {
            logger.warn(
                "Agent '{}' has a heartbeat but no home chat, so a beat reaches nobody through \"home\": send /home " +
                    "in the chat that should be its home, or set its channels.<instance>.home in the config",
                heartbeat.agent,
            )
        }
        loop = scope.launch(CoroutineName("heartbeats")) { supervise() }
    }

    override suspend fun onClose() {
        loop?.cancelAndJoin()
    }

    /** When the next beat of [heartbeat] is due, null before the beats start or when none is due within a week. */
    fun nextBeat(heartbeat: Heartbeat): Instant? {
        val start = started ?: return null
        val now = time.now()
        val every = heartbeat.every.toJavaDuration()
        var due = start.plus(every.multipliedBy(java.time.Duration.between(start, now).dividedBy(every) + 1))
        val horizon = now.plus(HORIZON)
        while (due <= horizon) {
            val hours = heartbeat.activeHours
            if (hours == null || LocalTime.ofInstant(due, heartbeat.zone) in hours) return due
            due = due.plus(every)
        }
        return null
    }

    private suspend fun supervise() {
        var failures = 0
        while (true) {
            try {
                started = time.now()
                Beats(heartbeats.all.map { it.plan }, Beater { beat(it) }, this, time).run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                val pause = backOff(failures)
                logger.error("The heartbeats stopped, restarting them in {}", pause, e)
                time.sleepUntil(time.now().plus(pause.toJavaDuration()))
            }
        }
    }

    private suspend fun beat(agent: AgentId) {
        val heartbeat = checkNotNull(heartbeats.of(agent))
        val turn = InitiatedTurn.heartbeat(agent, heartbeat.prompt).trust(Trust.TRUSTED).build()
        when (val admission = initiator.initiate(turn)) {
            is Admission.Accepted -> {
                refusals.remove(agent)
                when (val outcome = admission.ticket.outcome()) {
                    is TurnOutcome.Failed -> logger.warn("A heartbeat of agent '{}' failed: {}", agent, outcome.message)
                    else -> logger.debug("A heartbeat of agent '{}' ended: {}", agent, outcome)
                }
            }

            is Admission.Refused -> refused(agent, admission.reason)
        }
    }

    private fun refused(agent: AgentId, reason: RefusalReason) {
        if (!refusals.computeIfAbsent(agent) { ConcurrentHashMap.newKeySet() }.add(reason)) return
        when (reason) {
            RefusalReason.UNKNOWN_CHAT -> logger.warn(
                "Agent '{}' has a heartbeat but is no configured agent, so it does not beat: add it under " +
                    "agent.agents, or remove its heartbeat",
                agent,
            )

            else -> logger.warn(
                "A heartbeat of agent '{}' was refused: {}; the same refusal is not logged again until a beat runs",
                agent,
                reason,
            )
        }
    }

    override fun skipped(id: AgentId, due: Instant, why: BeatSkip) {
        logger.debug("A heartbeat of agent '{}' due at {} was skipped: {}", id, due, why)
    }

    override fun failed(id: AgentId, error: Exception) {
        logger.warn("A heartbeat of agent '{}' failed", id, error)
    }

    private fun backOff(failures: Int): Duration =
        (5.seconds * (1 shl (failures - 1).coerceAtMost(6))).coerceAtMost(5.minutes)
}

private val HORIZON = java.time.Duration.ofDays(7)

private val logger = LoggerFactory.getLogger(HeartbeatRunner::class.java)
