package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import java.nio.file.Path

/** Where `allowedit` lets a turn's file writes run without asking. */
internal fun interface EditPlaces {
    /** Whether [file] (absolute) lies where [turn]'s writes may land. */
    fun covers(turn: TurnInfo, file: Path): Boolean
}
