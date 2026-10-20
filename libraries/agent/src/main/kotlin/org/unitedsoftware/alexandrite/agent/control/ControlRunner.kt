package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.delivery.Notices
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.routing.ChatRouter
import org.unitedsoftware.alexandrite.agent.routing.Resolution
import org.unitedsoftware.alexandrite.agent.worker.RunningControl
import org.unitedsoftware.alexandrite.agent.worker.RunningTurn
import org.unitedsoftware.alexandrite.agent.worker.TurnWorkers
import org.unitedsoftware.alexandrite.agent.worker.WorkerJob
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException

/** What the agent's host APIs share: the agent that serves a chat, refusals for the user and changes on a worker. */
@Singleton
internal class ControlRunner(
    private val router: ChatRouter,
    private val notices: Notices,
    private val workers: TurnWorkers,
) {
    suspend fun served(chat: ChatAddress): Resolution.Served =
        router.resolve(chat) as? Resolution.Served ?: throw Refused(TextKeys.NO_AGENT, "chat" to chat)

    /** The key of the agent that serves [chat], refused with a [ControlRefusedException] when none does. */
    suspend fun key(chat: ChatAddress): AgentChatKey = refusing(null) { served(chat).key }

    /**
     * What [block] returns, where a [Refused] change becomes a [ControlRefusedException] in the language of the chat at
     * [key], or in the host's language when it is null.
     */
    suspend fun <T> refusing(key: AgentChatKey?, block: suspend () -> T): T = try {
        block()
    } catch (e: Refused) {
        val text = if (key == null) notices.text(e.key, *e.args) else notices.text(key, e.key, *e.args)
        throw ControlRefusedException(text, e)
    }

    /**
     * Throws [IllegalStateException] where a change cannot wait for the worker of [key]: in a turn, in a change of
     * [key] or a hook it fires, or once the agent stops.
     */
    suspend fun checkWaiting(key: AgentChatKey) {
        val context = currentCoroutineContext()
        check(context[RunningTurn] == null) {
            "A turn cannot wait for a change of a chat, which runs after the turns queued at the chat: make it " +
                "outside the turn."
        }
        check(context[RunningControl]?.workerKey != key) {
            "A change of a chat, or a hook it fires, cannot wait for another change of the chat, which runs after it."
        }
        check(workers.accepting) { "The agent is not running." }
    }

    /**
     * What [effect] returns, run at [key] once the turns queued there before it have ended, before [after] runs with
     * it there.
     */
    suspend fun <T> onWorker(key: AgentChatKey, after: suspend (T) -> Unit = {}, effect: suspend () -> T): T {
        checkWaiting(key)
        val result = CompletableDeferred<T>()
        val job = WorkerJob.Control {
            val value = effect()
            result.complete(value)
            after(value)
        }
        workers.control(key, job)
        val command = currentCoroutineContext()[RunningCommand]
        try {
            if (command == null) job.done.await() else command.waitingOnWorker { job.done.await() }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!result.isCompleted) throw IllegalStateException("The agent stopped before the change was made.", e)
        } catch (e: Exception) {
            if (!result.isCompleted) throw e
            logger.warn("A change of {} is made, but what follows it failed: {}", key, e.toString())
        }
        return result.await()
    }

    /** Runs [effect] at [key] once the turns queued there have ended, without waiting, logging a failure as [what]. */
    fun detached(key: AgentChatKey, what: String, effect: suspend () -> Unit) {
        val job = WorkerJob.Control { effect() }
        try {
            workers.control(key, job)
        } catch (e: IllegalStateException) {
            logger.warn("{} of {} is not made: {}", what, key, e.message)
            return
        }
        job.done.invokeOnCompletion { error ->
            if (error != null) logger.warn("{} of {} is not made: {}", what, key, error.toString())
        }
    }
}

private val logger = LoggerFactory.getLogger(ControlRunner::class.java)
