package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.tool.CallReview

/** Runs no call in a sandbox. */
internal object NotContained : Containments {
    override fun containment(turn: TurnInfo, review: CallReview): Containment? = null
}
