package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.agent.trust.CallTrust
import org.unitedsoftware.alexandrite.agent.trust.Foreseen
import org.unitedsoftware.alexandrite.agent.trust.Foresight
import org.unitedsoftware.alexandrite.agent.trust.MarkOffers
import org.unitedsoftware.alexandrite.agent.trust.Root
import org.unitedsoftware.alexandrite.agent.trust.TrustRules
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustRule

/** Trusts no call. */
internal object NoTrust : TrustRules {
    override fun trust(call: CheckedCall): CallTrust = CallTrust.NONE

    override fun untrustedPrograms(call: CheckedCall): List<List<String>> = emptyList()

    override fun inForce(agent: AgentId, conversation: ConversationId): List<TrustRule> = emptyList()

    override suspend fun grant(agent: AgentId, conversation: ConversationId, text: String, by: ChatUser?): TrustRule =
        error("Nothing is trusted.")

    override suspend fun revoke(agent: AgentId, conversation: ConversationId, text: String, by: ChatUser?) =
        Revocation(removed = false, declared = false)
}

/** Foresees no untrusted content. */
internal object NoForesight : Foresight {
    override suspend fun foresee(call: CheckedCall): Foreseen? = null

    override suspend fun roots(call: CheckedCall, foreseen: Foreseen, whole: Boolean): List<Root> = emptyList()

    override suspend fun grant(roots: List<Root>, turn: TurnInfo, by: ChatUser): List<Root> = emptyList()
}

/** Marks the conversation and offers nothing. */
internal object NoMarkOffers : MarkOffers {
    override suspend fun marked(call: GateCall, reason: String, roots: List<Root>) {
        call.taint.mark(reason)
    }
}
