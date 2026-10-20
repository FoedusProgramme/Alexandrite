package org.unitedsoftware.alexandrite.agent.usage

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Records the usage of each model call made in a turn's coroutines, once the turn is known. */
internal class UsageMeter(private val budgets: Budgets) : AbstractCoroutineContextElement(UsageMeter) {
    @Volatile
    var turn: TurnInfo? = null

    suspend fun record(model: ModelRef, usage: Usage) {
        val turn = turn ?: return
        withContext(NonCancellable) { budgets.record(turn.agent, turn.chat, model, usage) }
    }

    companion object Key : CoroutineContext.Key<UsageMeter>
}

/** Records [usage] of [model] for the turn whose coroutine this is, if it has a [UsageMeter]. */
internal suspend fun metered(model: ModelRef, usage: Usage) {
    currentCoroutineContext()[UsageMeter]?.record(model, usage)
}
