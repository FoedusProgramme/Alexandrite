package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.Records
import org.foedusprogramme.alexandrite.sdk.store.collection

/** The calls that wait for an approver, as records that a restart reads. */
@Singleton
internal class AwaitingCalls(records: Records) {
    private val awaiting = records.collection<AwaitingCall>(COLLECTION)

    suspend fun put(turn: TurnId, call: ToolCallId, request: String) {
        awaiting.put(key(turn, call), AwaitingCall(turn, call, request), null)
    }

    suspend fun remove(turn: TurnId, call: ToolCallId) {
        awaiting.remove(key(turn, call))
    }

    /** The request that each call waits for, by its turn and its id. */
    suspend fun all(): Map<Pair<TurnId, ToolCallId>, String> =
        awaiting.list().associate { (it.value.turn to it.value.call) to it.value.request }

    suspend fun clear() {
        awaiting.list().forEach { awaiting.remove(it.key) }
    }

    private fun key(turn: TurnId, call: ToolCallId): String = "$turn/$call"

    private companion object {
        const val COLLECTION = "awaiting"
    }
}

@Serializable
internal class AwaitingCall(val turn: TurnId, val call: ToolCallId, val request: String)
