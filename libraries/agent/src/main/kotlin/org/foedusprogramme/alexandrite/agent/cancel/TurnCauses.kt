package org.foedusprogramme.alexandrite.agent.cancel

import kotlinx.coroutines.CancellationException
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser

/** Cancels a turn at the request of [by], or of the host when it is null. */
internal class TurnCancelled(val by: ChatUser?) : CancellationException("The turn was cancelled.")

/** Cuts a turn off because the agent is shutting down. */
internal class TurnShutdown : CancellationException("The agent is shutting down.")

/** The [TurnCancelled] or [TurnShutdown] that [error] comes from, null when it comes from neither. */
internal fun turnCause(error: CancellationException): CancellationException? =
    generateSequence<Throwable>(error) { it.cause }.filterIsInstance<CancellationException>()
        .firstOrNull { it is TurnCancelled || it is TurnShutdown }
