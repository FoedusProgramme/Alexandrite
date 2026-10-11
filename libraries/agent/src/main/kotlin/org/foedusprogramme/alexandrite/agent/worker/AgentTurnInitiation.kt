package org.foedusprogramme.alexandrite.agent.worker

import kotlinx.coroutines.CancellationException
import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.routing.ChatRouter
import org.foedusprogramme.alexandrite.agent.routing.ChatTopology
import org.foedusprogramme.alexandrite.agent.routing.Resolution
import org.foedusprogramme.alexandrite.agent.turn.TurnPlan
import org.foedusprogramme.alexandrite.agent.turn.TurnSource
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.AgentSource
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiation
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * Queues each turn that a plugin starts on the worker of the agent it runs as.
 *
 * A turn for the agent that serves a chat waits here, counted per chat, while the router reads which agent that is.
 */
@Singleton
@Binds(TurnInitiation::class)
internal class AgentTurnInitiation(
    settings: AgentSettings,
    private val lock: SubmitLock,
    private val workers: TurnWorkers,
    private val router: ChatRouter,
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
    private val channels: ChannelDirectory,
    private val clock: Clock,
) : TurnInitiation {
    private val perKey = settings.queue.perKey

    /** The turns of each chat whose agent the router is reading, in the order they came. */
    private val held = HashMap<ChatAddress, ArrayDeque<Initiation>>()

    override fun initiate(plugin: String, turn: InitiatedTurn): Admission {
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
        val route = when {
            agent != null -> named(agent, chat)
            chat in held -> null
            else -> router.cached(chat)
        }
        return when {
            !workers.accepting -> Admission.Refused(RefusalReason.SHUTTING_DOWN)
            route == Resolution.Unknown -> Admission.Refused(RefusalReason.UNKNOWN_CHAT)
            route == Resolution.NoAgent -> Admission.Refused(RefusalReason.NO_AGENT)
            route is Resolution.Served -> workers.submit(route.key, job(initiation, route.key))
            else -> hold(initiation)
        }
    }

    /** How a turn that runs as [agent] at [chat] resolves. */
    private fun named(agent: AgentId, chat: ChatAddress): Resolution = when {
        chat.instance !in channels.instances -> Resolution.Unknown
        directory.attached(chat.instance).none { it.id == agent } -> Resolution.NoAgent
        else -> Resolution.Served(topology.key(agent, chat), chat, AgentSource.SELECTED)
    }

    private fun hold(initiation: Initiation): Admission {
        val chat = initiation.turn.chat
        val queue = held[chat] ?: ArrayDeque<Initiation>().also {
            held[chat] = it
            load(chat)
        }
        if (queue.size >= perKey) return Admission.Refused(RefusalReason.QUEUE_FULL, perKey)
        queue.addLast(initiation)
        return Admission.Accepted(initiation.ticket)
    }

    /** Reads which agent serves [chat], then queues the turns held for it in order. */
    private fun load(chat: ChatAddress) {
        val loading = lock.launch {
            val resolution = try {
                router.resolve(chat)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Cannot read which agent chat {} switched to: {}", chat, e.toString())
                null
            }
            lock.locked { flush(chat, resolution) }
        }
        loading.invokeOnCompletion { error ->
            if (error != null) lock.locked { flush(chat, null) }
        }
    }

    private fun flush(chat: ChatAddress, resolution: Resolution?) {
        for (initiation in held.remove(chat).orEmpty()) {
            when {
                !workers.accepting -> initiation.ticket.complete(TurnOutcome.ShutDown(replayable = true))

                resolution is Resolution.Served ->
                    workers.enqueue(resolution.key, job(initiation, resolution.key))

                else -> initiation.ticket.complete(TurnOutcome.Failed("Cannot read which agent serves $chat."))
            }
        }
    }

    /** Drops [turn] while it is held, as [cause] ends it, and returns whether it was held. */
    fun cancelHeld(turn: TurnId, cause: CancellationException): Boolean = lock.locked {
        val initiation = held.values.firstNotNullOfOrNull { queue -> queue.firstOrNull { it.id == turn } }
            ?: return@locked false
        held.getValue(initiation.turn.chat).remove(initiation)
        initiation.ticket.complete(notStarted(cause))
    }

    /** Ends every held turn as never started. */
    fun shutDown() {
        lock.locked {
            held.values.flatten().forEach { it.ticket.complete(TurnOutcome.ShutDown(replayable = true)) }
            held.clear()
        }
    }

    /** Drops [turn] while it is held or queued, cancels it while it runs, and returns whether it was found. */
    private fun cancel(turn: TurnId): Boolean =
        cancelHeld(turn, TurnCancelled(null)) || workers.cancel(turn, TurnCancelled(null))

    private fun job(initiation: Initiation, key: AgentChatKey): WorkerJob.Turn {
        val turn = initiation.turn
        val plan = TurnPlan(
            id = initiation.id,
            key = key,
            origin = turn.chat,
            kind = turn.kind,
            input = TurnSource.FromInitiated(initiation.plugin, turn.prompt, turn.trust),
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
