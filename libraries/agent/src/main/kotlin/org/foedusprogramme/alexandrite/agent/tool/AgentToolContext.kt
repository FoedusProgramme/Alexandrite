package org.foedusprogramme.alexandrite.agent.tool

import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext

/** The context of the call [call] in [turn], which holds only until [close]. */
internal class AgentToolContext(turn: TurnInfo, call: ToolCallId) : ToolContext {
    @Volatile
    private var open = true

    override val turn: TurnInfo = turn
        get() = field.also { checkOpen() }

    override val call: ToolCallId = call
        get() = field.also { checkOpen() }

    fun close() {
        open = false
    }

    private fun checkOpen() {
        check(open) { "A tool context is valid only while its call runs." }
    }
}
