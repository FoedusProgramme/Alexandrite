package org.foedusprogramme.alexandrite.testkit.store

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.testkit.StoreCheck

private const val ROUNDS = 5

internal val CANCELLATION_CHECKS: List<StoreCheck> = listOf(
    StoreCheck("a call cancelled while it runs either returns and takes effect, or does neither") {
        open {
            val conversation = conversations.current(main)
            repeat(ROUNDS) { round ->
                val put = returns { records.put("notes", "cancelled", "k$round", "$round", null) }
                val stored = records.get("notes", "cancelled", "k$round") != null
                expect(put == stored) { "A cancelled records.put ${outcome(put)}, but the record ${kept(stored)}." }

                val turn = newTurn(conversation)
                val started = returns { conversations.startTurn(turn) }
                val recorded = conversations.turn(turn.id) != null
                expect(started == recorded) {
                    "A cancelled conversations.startTurn ${outcome(started)}, but the turn ${kept(recorded)}."
                }
            }
        }
    },
)

/** Whether [call] returned when it was cancelled at its first suspension. */
private suspend fun returns(call: suspend () -> Unit): Boolean {
    var returned = false
    coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) {
            call()
            returned = true
        }.cancel()
    }
    return returned
}

private fun outcome(returned: Boolean): String = if (returned) "returned" else "was cancelled"

private fun kept(stored: Boolean): String = if (stored) "was stored" else "was not stored"
