package org.unitedsoftware.alexandrite.automation.heartbeat

import org.unitedsoftware.alexandrite.automation.common.ActiveHours
import org.unitedsoftware.alexandrite.automation.common.BeatPlan
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration

internal class Heartbeat(
    val agent: AgentId,
    val every: Duration,
    val prompt: String,
    val activeHours: ActiveHours?,
    val zone: ZoneId,
) {
    val plan: BeatPlan<AgentId> get() = BeatPlan(agent, every, zone, activeHours)
}

/** The configured heartbeats. */
@Singleton
internal class Heartbeats(
    config: HeartbeatConfig,
    clock: Clock,
    private val control: AgentControl?,
    private val channels: ChannelDirectory,
) {
    /** In config order. */
    val all: List<Heartbeat> = config.agents.map { (id, beat) ->
        Heartbeat(
            AgentId(id),
            checkNotNull(interval(beat.every)),
            beat.prompt ?: DEFAULT_PROMPT,
            beat.activeHours?.let(ActiveHours::parse),
            beat.zone?.let(::zoneOrNull) ?: clock.zone,
        )
    }

    private val byAgent = all.associateBy { it.agent }

    fun of(agent: AgentId): Heartbeat? = byAgent[agent]

    /** Whether [agent] has no home chat on any channel instance, false when no agent can tell. */
    fun homeless(agent: AgentId): Boolean {
        val control = control ?: return false
        return channels.instances.filter { it != ChatAddress.AGENTS }
            .none { instance -> control.agents(instance).any { it.id == agent && it.home != null } }
    }
}

/** What a beat asks the agent when its heartbeat names no prompt. */
internal val DEFAULT_PROMPT: String = """
    This is a heartbeat: a turn that comes on a timer. It belongs to no chat, and your reply reaches nobody.
    Look at the time, your notes and your running jobs. If something needs someone's attention, tell them briefly
    with chat.send, to "home" or to a chat from chat.list. Otherwise end without a reply. Do not start a
    conversation just to fill the silence.
""".trimIndent().replace("\n", " ")
