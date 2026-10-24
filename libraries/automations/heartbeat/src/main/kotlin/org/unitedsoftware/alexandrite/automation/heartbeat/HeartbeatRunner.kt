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
import org.unitedsoftware.alexandrite.automation.common.restarting
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.config.ConfigSnapshot
import org.unitedsoftware.alexandrite.sdk.config.LiveConfig
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Beats the configured heartbeats while the plugin is open, each beat a heartbeat turn of its agent. */
@Singleton
internal class HeartbeatRunner(
    private val heartbeats: Heartbeats,
    private val initiator: TurnInitiator,
    private val scope: PluginScope,
    clock: Clock,
    time: TimeSource?,
) : Lifecycle,
    BeatEvents<AgentId> {
    private val time = time ?: TimeSource.of(clock)
    private val beats = Beats(heartbeats.all.map { it.plan }, Beater { beat(it) }, this, this.time)

    /** The refusals logged per agent since its last admitted beat. */
    private val refusals = ConcurrentHashMap<AgentId, MutableSet<RefusalReason>>()

    @Volatile
    private var started: Instant? = null
    private var loop: Job? = null

    /** What beats the heartbeats of [config] in place of these. */
    fun take(config: HeartbeatConfig): () -> Unit {
        val next = heartbeats.read(config)
        return {
            val added = next.filter { heartbeats.of(it.agent) == null }
            heartbeats.replace(next)
            beats.update(next.map { it.plan })
            val beating = next.joinToString { "${it.agent} every ${it.every}" }.ifEmpty { "none" }
            logger.info("The heartbeats are now {}", beating)
            warnHomeless(added)
        }
    }

    override suspend fun onOpen() {
        if (heartbeats.all.isEmpty()) logger.debug("No agent has a heartbeat")
        warnHomeless(heartbeats.all)
        loop = scope.launch(CoroutineName("heartbeats")) { supervise() }
    }

    private fun warnHomeless(beating: List<Heartbeat>) {
        for (heartbeat in beating.filter { heartbeats.homeless(it.agent) }) {
            logger.warn(
                "Agent '{}' has a heartbeat but no home chat, so a beat reaches nobody through \"home\": send /home " +
                    "in the chat that should be its home, or set its channels.<instance>.home in the config",
                heartbeat.agent,
            )
        }
    }

    override suspend fun onClose() {
        loop?.cancelAndJoin()
    }

    /** When the next beat of [heartbeat] is due, null before the beats start or when none is due within a week. */
    fun nextBeat(heartbeat: Heartbeat): Instant? {
        val start = started ?: return null
        val now = time.now()
        return heartbeat.plan.nextActive(start, now, now.plus(HORIZON))
    }

    private suspend fun supervise() {
        restarting(
            time,
            first = 5.seconds,
            last = 5.minutes,
            steady = Duration.INFINITE,
            failed = { e, pause -> logger.error("The heartbeats stopped, restarting them in {}", pause, e) },
        ) {
            beats.run(time.now().also { started = it })
        }
    }

    private suspend fun beat(agent: AgentId) {
        val heartbeat = heartbeats.of(agent) ?: return
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
}

/** Takes the changes of the heartbeats while the runtime runs. */
@Singleton
@Contribute(LiveConfig::class)
internal class HeartbeatChanges(private val runner: Lazy<HeartbeatRunner>) : LiveConfig {
    override val paths: List<String> = listOf("agents")

    override fun prepare(config: ConfigSnapshot): () -> Unit =
        runner.value.take(config.section(key<HeartbeatConfig>()) ?: HeartbeatConfig())
}

private val HORIZON = java.time.Duration.ofDays(7)

private val logger = LoggerFactory.getLogger(HeartbeatRunner::class.java)
