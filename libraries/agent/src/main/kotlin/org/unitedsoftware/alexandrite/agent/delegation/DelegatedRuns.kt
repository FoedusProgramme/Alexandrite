package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.TurnPhase
import org.unitedsoftware.alexandrite.sdk.turn.TurnStatus
import java.time.Instant

/** The sub-agent runs that go on now. */
@Singleton
internal class DelegatedRuns {
    private val lock = Any()
    private val live = LinkedHashMap<RunId, LiveRun>()

    fun started(run: LiveRun) {
        synchronized(lock) { live[run.delegation.run] = run }
    }

    fun ended(run: RunId) {
        synchronized(lock) { live.remove(run) }
    }

    /** Cancels [run] with [cause], and every run below it when [tree], and returns whether [run] went on. */
    fun cancel(run: RunId, tree: Boolean, cause: CancellationException): Boolean {
        val cancelled = synchronized(lock) {
            val target = live[run] ?: return false
            val runs = listOf(target) + if (tree) below(target) else emptyList()
            runs.sortedByDescending { it.delegation.lineage.depth }
        }
        cancelled.forEach { it.job.cancel(cause) }
        return true
    }

    /** Cancels the run whose turn is [turn] with [cause], and returns whether one went on. */
    fun cancelTurn(turn: TurnId, cause: CancellationException): Boolean {
        val run = synchronized(lock) { live.values.firstOrNull { it.turn == turn } } ?: return false
        run.job.cancel(cause)
        return true
    }

    /** The turns of the runs that go on below a turn from [chat], or from every chat when it is null. */
    fun statuses(chat: ChatAddress?): List<TurnStatus> = synchronized(lock) {
        live.values.mapNotNull { run ->
            val turn = run.delegation.turn?.takeIf { chat == null || it.chat == chat } ?: return@mapNotNull null
            TurnStatus.builder(turn, TurnPhase.RUNNING, run.startedAt).startedAt(run.startedAt).build()
        }
    }

    /** The runs below [run], while the caller holds [lock]. */
    private fun below(run: LiveRun): List<LiveRun> {
        val children = live.values.filter { it.delegation.lineage.parentTurn == run.turn }
        return children + children.flatMap(::below)
    }
}

/** The run of [delegation], whose turn [turn] runs as [job] since [startedAt]. */
internal class LiveRun(val delegation: Delegation, val turn: TurnId, val job: Job, val startedAt: Instant)
