package org.unitedsoftware.alexandrite.agent.worker

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.permission.Mandates
import org.unitedsoftware.alexandrite.agent.routing.ChatRouter
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.agent.routing.Resolution
import org.unitedsoftware.alexandrite.agent.turn.TurnPlan
import org.unitedsoftware.alexandrite.agent.turn.TurnSource
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.AgentSource
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.Mandate
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.requireInitiable
import java.time.Clock
import java.time.Instant

/** Queues each turn that a plugin starts on the worker of the agent it runs as. */
@Singleton
@Binds(TurnInitiation::class)
internal class AgentTurnInitiation(
    private val lock: SubmitLock,
    private val workers: TurnWorkers,
    private val router: ChatRouter,
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
    private val channels: ChannelDirectory,
    private val mandates: Mandates,
    private val clock: Clock,
) : TurnInitiation {
    override fun initiate(plugin: String, turn: InitiatedTurn): Admission {
        requireInitiable(plugin, turn)
        val now = clock.instant()
        val id = newTurnId(now)
        return try {
            lock.locked { admit(Initiation(id, plugin, turn, now, AgentTicket(id, ::cancel))) }
        } catch (e: Exception) {
            logger.error("Cannot take the turn {} that plugin '{}' started at {}", id, plugin, turn.chat, e)
            Admission.Accepted(AgentTicket.ended(id, TurnOutcome.Failed("The agent could not take it: $e")))
        }
    }

    private fun admit(initiation: Initiation): Admission {
        val chat = initiation.turn.chat
        val agent = initiation.turn.agent
        val route = if (agent != null) named(agent, chat) else router.resolve(chat)
        return when {
            !workers.accepting -> Admission.Refused(RefusalReason.SHUTTING_DOWN)
            route !is Resolution.Served -> Admission.Refused(refusal(route))
            else -> workers.submit(route.key, job(initiation, route.key))
        }
    }

    override suspend fun revoke(plugin: String, mandate: Mandate) {
        mandates.revoke(plugin, mandate)
    }

    /** How a turn that runs as [agent] at [chat] resolves, its own chat included. */
    private fun named(agent: AgentId, chat: ChatAddress): Resolution = when {
        chat == ChatAddress.ofAgent(agent) && directory.agent(agent) != null ->
            Resolution.Served(AgentChatKey(agent, chat), chat, AgentSource.SELECTED)

        chat.instance !in channels.instances -> Resolution.Unknown

        directory.attached(chat.instance).none { it.id == agent } -> Resolution.NoAgent

        else -> Resolution.Served(topology.key(agent, chat), chat, AgentSource.SELECTED)
    }

    private fun cancel(turn: TurnId): Boolean = workers.cancel(turn, TurnCancelled(null))

    private fun job(initiation: Initiation, key: AgentChatKey): WorkerJob.Turn {
        val turn = initiation.turn
        val plan = TurnPlan(
            id = initiation.id,
            key = key,
            origin = turn.chat,
            kind = turn.kind,
            input = TurnSource.FromInitiated(initiation.plugin, turn.prompt, turn.trust, turn.mandate),
            actor = null,
            replyTarget = ReplyTarget.CALLER,
            trigger = null,
            queuedAt = initiation.queuedAt,
            route = turn.route,
        )
        return WorkerJob.Turn(plan, initiation.ticket, Capacity.COUNTED)
    }
}

/** The [turn] that [plugin] started, queued as [id]. */
private class Initiation(
    val id: TurnId,
    val plugin: String,
    val turn: InitiatedTurn,
    val queuedAt: Instant,
    val ticket: AgentTicket,
)

private val logger = LoggerFactory.getLogger(AgentTurnInitiation::class.java)
