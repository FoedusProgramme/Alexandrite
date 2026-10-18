package org.foedusprogramme.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * The open streaming message of each qqbot turn, which an approval closes before the reply continues.
 *
 * A reply's stream belongs to the turn that opened it. Once an approval message is shown, the reply's final answer
 * cannot be moved below it; this registry lets the approval desk close the stream and remember that the answer is to
 * be sent as a message of its own.
 */
@Singleton
internal class QqBotProgress {
    private class Open(val chat: ChatAddress, val stream: QqBotStream)

    private val open = ConcurrentHashMap<TurnId, Open>()
    private val gates = ConcurrentHashMap<TurnId, Mutex>()
    private val deferred = ConcurrentHashMap.newKeySet<TurnId>()

    private fun gate(turn: TurnId): Mutex = gates.computeIfAbsent(turn) { Mutex() }

    /** Makes [stream] the streaming message of [turn]. */
    suspend fun attach(turn: TurnId, chat: ChatAddress, stream: QqBotStream) {
        gate(turn).withLock {
            if (turn in deferred) return@withLock
            open.getOrPut(turn) { Open(chat, stream) }
        }
    }

    /**
     * Closes the stream of [turn] before an approval message, whose answer then arrives as an ordinary message after it.
     *
     * A streaming message cannot be moved below the approval request once more of it is written, so the turn's final
     * answer is sent as a message of its own instead.
     */
    suspend fun defer(turn: TurnId) {
        gate(turn).withLock {
            open.remove(turn)?.let { entry -> entry.stream.finish(entry.stream.shownText) }
            deferred += turn
        }
    }

    /** Whether the final answer of [turn] is to be sent outside its stream, after an approval request. */
    fun isDeferred(turn: TurnId): Boolean = turn in deferred

    /** Done with [turn], whose deferred answer was sent or whose stream is its own from here. */
    fun done(turn: TurnId) {
        deferred -= turn
        open.remove(turn)
    }

    /** Forgets [turn], which ended without a stream to close. */
    suspend fun abandon(turn: TurnId) {
        deferred -= turn
        gate(turn).withLock { open.remove(turn) }
    }
}
