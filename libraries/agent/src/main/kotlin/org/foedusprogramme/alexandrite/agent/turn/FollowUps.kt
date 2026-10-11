package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import java.time.Instant

/** The messages queued at a running turn's key, which the turn may take as follow-ups. */
internal interface FollowUps {
    /** Takes the messages that [principal] sent in [chat] since [since] off the queue, in arrival order. */
    fun take(principal: UserAddress, chat: ChatAddress, since: Instant): List<FollowUp>

    /** Puts [followUps], taken and not absorbed, back at the front of the queue. */
    fun restore(followUps: List<FollowUp>)
}

/** A queued message that a running turn took, whose own turn ends with what [complete] is given. */
internal class FollowUp(val plan: TurnPlan, val complete: (TurnOutcome) -> Unit)
