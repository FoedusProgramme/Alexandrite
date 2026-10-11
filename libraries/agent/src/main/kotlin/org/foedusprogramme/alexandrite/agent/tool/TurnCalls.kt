package org.foedusprogramme.alexandrite.agent.tool

import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId

/** What a turn does for its tool calls besides running them. */
internal interface TurnCalls {
    /** Notes that the tool of [call] starts. */
    fun starting(call: ToolCallId)

    /** Whether the turn's principal sent a message that joins the turn at its next tool boundary. */
    fun followedUp(): Boolean

    /**
     * What [ask] returns for [call], which waits for the approval request [request] without the turn's permit, or null
     * once the turn's principal sends a message that joins the turn.
     */
    suspend fun <T : Any> awaitApproval(call: ToolCallId, request: String, ask: suspend () -> T): T?
}
