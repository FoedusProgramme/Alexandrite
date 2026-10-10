package org.foedusprogramme.alexandrite.agent.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.routing.ChatRouter
import org.foedusprogramme.alexandrite.agent.routing.Resolution
import org.foedusprogramme.alexandrite.agent.turn.TurnCancelled
import org.foedusprogramme.alexandrite.agent.turn.TurnPlan
import org.foedusprogramme.alexandrite.agent.turn.TurnSource
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * One intake per chat, created on demand and removed once idle, which hands the chat's messages to the workers in
 * submit order.
 *
 * It holds them while a command of the chat gates it and while the router reads which agent the chat switched to.
 */
@Singleton
internal class ChatIntakes(
    settings: AgentSettings,
    private val lock: SubmitLock,
    private val workers: TurnWorkers,
    private val router: ChatRouter,
) {
    private val perKey = settings.queue.perKey
    private val gateTimeout = settings.queue.commandGateSeconds.seconds
    private val intakes = HashMap<ChatAddress, Intake>()

    /** The counted messages held for each key. */
    private val heldAt = HashMap<AgentChatKey, Int>()

    val chats: Set<ChatAddress> get() = lock.locked { intakes.keys.toSet() }

    fun dispatch(turn: TurnId, message: IncomingMessage, capacity: Capacity, queuedAt: Instant): Admission =
        lock.locked {
            val chat = message.chat
            val held = Held(turn, message, capacity, queuedAt, AgentTicket(turn) { cancel(it, TurnCancelled(null)) })
            val route = router.cached(chat)
            when {
                !workers.accepting -> Admission.Refused(RefusalReason.SHUTTING_DOWN)

                route == Resolution.Unknown -> Admission.Refused(RefusalReason.UNKNOWN_CHAT)

                route == Resolution.NoAgent -> Admission.Refused(RefusalReason.NO_AGENT)

                route is Resolution.Served && chat !in intakes ->
                    workers.submit(route.key, turn(held, route.key), heldAt[route.key] ?: 0)

                else -> hold(held, (route as? Resolution.Served)?.key)
            }
        }

    /** Holds the chat's messages while [command] runs, for at most `queue.commandGateSeconds`. */
    fun gate(chat: ChatAddress, command: TurnId) {
        lock.locked {
            val intake = intakes.getOrPut(chat) { Intake(chat) }
            if (command !in intake.gates) {
                intake.gates[command] = lock.launch {
                    delay(gateTimeout)
                    logger.warn("Command {} held the messages of {} for {}: they go on", command, chat, gateTimeout)
                    release(chat, command)
                }
            }
        }
    }

    /** Lets the messages that [command] held go on. */
    fun release(chat: ChatAddress, command: TurnId) {
        lock.locked {
            val intake = intakes[chat] ?: return@locked
            val timer = intake.gates.remove(command) ?: return@locked
            timer.cancel()
            flush(intake)
        }
    }

    /** Drops [turn] while it is held or queued, cancels it while it runs, and returns whether it was found. */
    fun cancel(turn: TurnId, cause: CancellationException): Boolean = lock.locked {
        for (intake in intakes.values) {
            val held = intake.held.firstOrNull { it.turn == turn } ?: continue
            intake.held.remove(held)
            uncount(held)
            held.ticket.complete(notStarted(cause))
            reclaim(intake)
            return@locked true
        }
        workers.cancel(turn, cause)
    }

    /** Ends every held message as never started. */
    fun shutDown() {
        lock.locked {
            for (intake in intakes.values) {
                intake.held.forEach { it.ticket.complete(TurnOutcome.ShutDown(replayable = true)) }
                intake.gates.values.forEach(Job::cancel)
            }
            intakes.clear()
            heldAt.clear()
        }
    }

    private fun hold(held: Held, key: AgentChatKey?): Admission {
        val chat = held.message.chat
        val intake = intakes[chat]
        if (held.capacity == Capacity.COUNTED) {
            val waiting = if (key == null) {
                intake?.held.orEmpty().count { it.capacity == Capacity.COUNTED }
            } else {
                workers.waiting(key) + (heldAt[key] ?: 0)
            }
            if (waiting >= perKey) return Admission.Refused(RefusalReason.QUEUE_FULL, perKey)
            if (key != null) {
                held.countedAt = key
                heldAt.merge(key, 1, Int::plus)
            }
        }
        val target = intake ?: Intake(chat).also { intakes[chat] = it }
        target.held.addLast(held)
        if (key == null && !target.loading) load(target)
        return Admission.Accepted(held.ticket)
    }

    /** Hands the held messages of [intake] to the workers while nothing holds them. */
    private fun flush(intake: Intake) {
        while (intake.gates.isEmpty() && intake.held.isNotEmpty()) {
            val route = router.cached(intake.chat)
            if (route == null) {
                if (!intake.loading) load(intake)
                return
            }
            val held = intake.held.removeFirst()
            uncount(held)
            if (route is Resolution.Served) {
                workers.enqueue(route.key, turn(held, route.key))
            } else {
                held.ticket.complete(TurnOutcome.Failed("No agent serves ${intake.chat}."))
            }
        }
        reclaim(intake)
    }

    /** Reads which agent the chat of [intake] switched to, then hands its held messages on. */
    private fun load(intake: Intake) {
        intake.loading = true
        lock.launch {
            val error = try {
                router.resolve(intake.chat)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            lock.locked {
                if (intakes[intake.chat] !== intake) return@locked
                intake.loading = false
                if (error == null) {
                    flush(intake)
                } else {
                    logger.warn("Cannot read which agent chat {} switched to: {}", intake.chat, error.toString())
                    intake.held.forEach {
                        uncount(it)
                        it.ticket.complete(TurnOutcome.Failed("Cannot read which agent serves ${intake.chat}: $error"))
                    }
                    intake.held.clear()
                    reclaim(intake)
                }
            }
        }
    }

    private fun uncount(held: Held) {
        val key = held.countedAt ?: return
        heldAt.compute(key) { _, count -> count?.minus(1)?.takeIf { it > 0 } }
    }

    private fun reclaim(intake: Intake) {
        if (intake.held.isEmpty() && intake.gates.isEmpty() && !intake.loading) intakes.remove(intake.chat)
    }

    private fun turn(held: Held, key: AgentChatKey): WorkerJob.Turn {
        val message = held.message
        val plan = TurnPlan(
            id = held.turn,
            key = key,
            origin = message.chat,
            kind = TurnKind.MESSAGE,
            input = TurnSource.FromMessage(message),
            actor = message.sender,
            replyTarget = ReplyTarget.CHAT,
            trigger = message.ref,
            queuedAt = held.queuedAt,
        )
        return WorkerJob.Turn(plan, held.ticket, held.capacity)
    }
}

private class Intake(val chat: ChatAddress) {
    val held = ArrayDeque<Held>()

    /** The commands that gate the chat, with the timers that release them. */
    val gates = LinkedHashMap<TurnId, Job>()
    var loading = false
}

private class Held(
    val turn: TurnId,
    val message: IncomingMessage,
    val capacity: Capacity,
    val queuedAt: Instant,
    val ticket: AgentTicket,
) {
    /** The key the message counts against, null for a chat not resolved yet. */
    var countedAt: AgentChatKey? = null
}

private val logger = LoggerFactory.getLogger(ChatIntakes::class.java)
