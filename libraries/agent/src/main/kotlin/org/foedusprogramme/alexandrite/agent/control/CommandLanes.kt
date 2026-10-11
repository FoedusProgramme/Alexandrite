package org.foedusprogramme.alexandrite.agent.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.delivery.Notices
import org.foedusprogramme.alexandrite.agent.delivery.delivered
import org.foedusprogramme.alexandrite.agent.delivery.notice
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.agent.routing.ChatRouter
import org.foedusprogramme.alexandrite.agent.routing.Resolution
import org.foedusprogramme.alexandrite.agent.worker.AgentTicket
import org.foedusprogramme.alexandrite.agent.worker.ChatIntakes
import org.foedusprogramme.alexandrite.agent.worker.SubmitLock
import org.foedusprogramme.alexandrite.agent.worker.TurnWorkers
import org.foedusprogramme.alexandrite.agent.worker.notStarted
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * Runs the command invocations that a handler claims, in a lane per chat beside the chat's turns.
 *
 * A lane admits `queue.commandsPerChat` counted invocations and runs those whose command runs alongside as they come,
 * the others one after another in the order they came.
 */
@Singleton
internal class CommandLanes(
    settings: AgentSettings,
    private val lock: SubmitLock,
    private val workers: TurnWorkers,
    private val intakes: ChatIntakes,
    private val router: ChatRouter,
    private val directory: AgentDirectory,
    private val conversations: ConversationStore,
    private val states: SettingsStates,
    private val notices: Notices,
    private val channels: ChannelDirectory,
    private val scope: PluginScope,
    private val clock: Clock,
) {
    private val perChat = settings.queue.commandsPerChat
    private val lanes = HashMap<ChatAddress, Lane>()

    /** Admits [command], which [handler] claims, and holds its chat's messages until the handler ends. */
    fun submit(turn: TurnId, command: Submission.Command, handler: CommandHandler, queuedAt: Instant): Admission =
        lock.locked {
            val chat = command.chat
            val route = router.cached(chat)
            val lane = lanes[chat]
            when {
                route == Resolution.Unknown -> Admission.Refused(RefusalReason.UNKNOWN_CHAT)

                route == Resolution.NoAgent -> Admission.Refused(RefusalReason.NO_AGENT)

                !workers.accepting -> Admission.Refused(RefusalReason.SHUTTING_DOWN)

                command.capacity == Capacity.COUNTED && lane != null && lane.size >= perChat ->
                    Admission.Refused(RefusalReason.QUEUE_FULL, perChat)

                else -> {
                    val ticket = AgentTicket(turn) { cancel(it, TurnCancelled(null)) }
                    val target = lane ?: Lane(chat).also { lanes[chat] = it }
                    val alongside = handler.commands.any {
                        it.runsAlongside && it.name.equals(command.invocation.name, ignoreCase = true)
                    }
                    target.waiting.addLast(Invocation(turn, command.invocation, handler, queuedAt, ticket, alongside))
                    intakes.gate(chat, turn)
                    startNext(target)
                    Admission.Accepted(ticket)
                }
            }
        }

    /** Drops [turn] while it waits or cancels it while it runs, and returns whether a lane held it. */
    fun cancel(turn: TurnId, cause: CancellationException): Boolean = lock.locked {
        for (lane in lanes.values.toList()) {
            val waiting = lane.waiting.firstOrNull { it.turn == turn }
            if (waiting != null) {
                lane.waiting.remove(waiting)
                waiting.ticket.complete(notStarted(cause))
                intakes.release(lane.chat, turn)
                reclaim(lane)
                return@locked true
            }
            val running = lane.running.firstOrNull { it.turn == turn }
            if (running != null) {
                if (running.cause == null) running.cause = cause
                running.job?.cancel(cause)
                return@locked true
            }
        }
        false
    }

    /** The commands that run in [chat], or in every chat when it is null. */
    fun statuses(chat: ChatAddress?): List<TurnStatus> = lock.locked {
        lanes.values.filter {
            chat == null || it.chat == chat
        }.flatMap { lane -> lane.running.mapNotNull { it.status() } }
    }

    /** Ends every invocation that has not started as never started. */
    fun shutDown() {
        lock.locked {
            for (lane in lanes.values.toList()) {
                lane.waiting.forEach {
                    it.ticket.complete(TurnOutcome.ShutDown(replayable = true))
                    intakes.release(lane.chat, it.turn)
                }
                lane.waiting.clear()
                reclaim(lane)
            }
        }
    }

    /** Answers [command], which nobody claims and no message carried, with a notice. */
    fun unknown(turn: TurnId, command: CommandInvocation): Admission {
        when (router.cached(command.chat)) {
            Resolution.Unknown -> return Admission.Refused(RefusalReason.UNKNOWN_CHAT)
            Resolution.NoAgent -> return Admission.Refused(RefusalReason.NO_AGENT)
            else -> Unit
        }
        val ticket = AgentTicket(turn) { false }
        val answer = scope.launch {
            ticket.complete(TurnOutcome.Completed(null, mapOf(command.chat to answer(command))))
        }
        answer.invokeOnCompletion { error ->
            when (error) {
                null -> Unit
                is CancellationException -> ticket.complete(TurnOutcome.ShutDown(replayable = true))
                else -> ticket.complete(TurnOutcome.Failed(error.toString()))
            }
        }
        return Admission.Accepted(ticket)
    }

    private suspend fun answer(command: CommandInvocation): Delivery {
        val chat = command.chat
        val channel = channels.channel(chat.instance)
            ?: return Delivery.NotDelivered(DeliveryFailure.TRANSIENT, "Channel instance ${chat.instance} is closed.")
        val served = router.resolve(chat) as? Resolution.Served
            ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "No agent serves $chat.")
        val text = notices.text(served.key, TextKeys.UNKNOWN_COMMAND, "command" to command.name)
        return delivered { channel.send(chat, notice(text, command.trigger, null)) }
    }

    private fun startNext(lane: Lane) {
        var serial = lane.running.any { !it.alongside }
        val starting = mutableListOf<Invocation>()
        for (invocation in lane.waiting) {
            if (!invocation.alongside) {
                if (serial) continue
                serial = true
            }
            starting += invocation
        }
        lane.waiting.removeAll(starting)
        lane.running += starting
        for (invocation in starting) {
            intakes.arm(lane.chat, invocation.turn)
            val job = lock.launch { run(invocation) }
            invocation.job = job
            job.invokeOnCompletion { error -> ended(lane, invocation, error) }
        }
    }

    private suspend fun run(invocation: Invocation) {
        lock.locked { invocation.startedAt = clock.instant() }
        val outcome = try {
            val turn = turn(invocation)
            lock.locked { invocation.info = turn }
            val context = AgentCommandContext(turn, invocation.command.trigger, channels)
            withContext(RunningCommand(turn)) { invocation.handler.handle(invocation.command, context) }
            TurnOutcome.Completed(null, context.deliveries)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            failed(invocation, e)
        } catch (e: Exception) {
            failed(invocation, e)
        }
        invocation.ticket.complete(outcome)
    }

    /** The COMMAND turn of [invocation] at the agent that serves its chat now. */
    private suspend fun turn(invocation: Invocation): TurnInfo {
        val command = invocation.command
        val served = router.resolve(command.chat) as? Resolution.Served
            ?: throw IllegalStateException("No agent serves ${command.chat}.")
        val key = served.key
        val language = states.language(key) ?: directory.agent(key.agent)?.language
        return TurnInfo.builder(invocation.turn, command.chat, conversations.current(key).id, TurnKind.COMMAND)
            .actor(command.issuer)
            .language(language)
            .agent(key.agent)
            .key(key)
            .build()
    }

    /** Tells the chat that the handler of [invocation] failed with [error]. */
    private suspend fun failed(invocation: Invocation, error: Exception): TurnOutcome {
        val command = invocation.command
        logger.error("Command /{} ({}) in {} failed", command.name, invocation.turn, command.chat, error)
        val turn = invocation.info
        val text = if (turn == null) {
            notices.text(TextKeys.COMMAND_FAILED, "command" to command.name)
        } else {
            notices.text(turn, TextKeys.COMMAND_FAILED, "command" to command.name)
        }
        val channel = channels.channel(command.chat.instance)
        val delivery = if (channel == null) {
            Delivery.NotDelivered(DeliveryFailure.TRANSIENT, "Channel instance ${command.chat.instance} is closed.")
        } else {
            delivered { channel.send(command.chat, notice(text, command.trigger, null)) }
        }
        if (delivery !is Delivery.Delivered) {
            logger.warn(
                "The failure notice of command {} did not reach {}: {}",
                invocation.turn,
                command.chat,
                delivery,
            )
        }
        return TurnOutcome.Failed(error.toString())
    }

    private fun ended(lane: Lane, invocation: Invocation, error: Throwable?) {
        if (error != null) invocation.ticket.complete(interrupted(invocation, error))
        lock.locked {
            lane.running.remove(invocation)
            intakes.release(lane.chat, invocation.turn)
            startNext(lane)
            reclaim(lane)
        }
    }

    /** How [invocation] ends when its coroutine ended with [error]. */
    private fun interrupted(invocation: Invocation, error: Throwable): TurnOutcome = lock.locked {
        when {
            error !is CancellationException -> TurnOutcome.Failed(error.toString())
            invocation.cause is TurnCancelled -> TurnOutcome.Cancelled
            else -> TurnOutcome.ShutDown(replayable = invocation.info == null)
        }
    }

    private fun reclaim(lane: Lane) {
        if (lane.waiting.isEmpty() && lane.running.isEmpty() && lanes[lane.chat] === lane) lanes.remove(lane.chat)
    }
}

private class Lane(val chat: ChatAddress) {
    val waiting = ArrayDeque<Invocation>()
    val running = mutableListOf<Invocation>()

    val size: Int get() = waiting.size + running.size
}

private class Invocation(
    val turn: TurnId,
    val command: CommandInvocation,
    val handler: CommandHandler,
    val queuedAt: Instant,
    val ticket: AgentTicket,
    /** Whether the command runs beside the lane's others. */
    val alongside: Boolean,
) {
    var job: Job? = null
    var startedAt: Instant? = null

    /** The invocation's turn, null until its agent and conversation are read. */
    var info: TurnInfo? = null
    var cause: CancellationException? = null

    fun status(): TurnStatus? {
        val turn = info ?: return null
        return TurnStatus.builder(turn, TurnPhase.RUNNING, queuedAt).startedAt(startedAt).build()
    }
}

private val logger = LoggerFactory.getLogger(CommandLanes::class.java)
