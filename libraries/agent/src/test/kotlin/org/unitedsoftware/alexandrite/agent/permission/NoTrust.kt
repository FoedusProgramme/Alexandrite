package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustRule

/** Trusts no call. */
internal object NoTrust : TrustRules {
    override fun trust(call: CheckedCall): CallTrust = CallTrust.NONE

    override fun inForce(agent: AgentId, conversation: ConversationId): List<TrustRule> = emptyList()

    override suspend fun grant(agent: AgentId, conversation: ConversationId, text: String, by: ChatUser?): TrustRule =
        error("Nothing is trusted.")

    override suspend fun revoke(agent: AgentId, conversation: ConversationId, text: String, by: ChatUser?) =
        Revocation(removed = false, declared = false)
}
