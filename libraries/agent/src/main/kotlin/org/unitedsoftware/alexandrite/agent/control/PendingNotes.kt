package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry

/** A part of the agent that tells the next turn of a conversation what happened there since the last one. */
@ContributedSpi
internal interface PendingNotes {
    /** Where its note sits among the others, lower first. */
    val order: Int

    /** The note for [turn], which offers [definitions], null when there is nothing to tell. */
    suspend fun pending(turn: TurnInfo, definitions: List<ToolDefinition>): PendingNote?
}

/**
 * What a turn is told first: the unstored [notice], null for none, why it taints the turn, null when it does not, and
 * what records it as [told] once the turn stored it.
 */
internal class PendingNote(val notice: UserEntry?, val taint: String?, val told: suspend () -> Unit)

/** The orders of the agent's own notes. */
internal object NoteOrder {
    const val TOOLS: Int = 10
    const val COMMANDS: Int = 20
    const val INTERRUPTED: Int = 30
    const val SENT: Int = 40
}
