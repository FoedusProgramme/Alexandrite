package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.permission.CallRules
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.turn.AllowRule
import org.unitedsoftware.alexandrite.sdk.turn.PermissionRules
import org.unitedsoftware.alexandrite.sdk.turn.Revocation

@Singleton
@Binds(PermissionRules::class)
internal class PermissionRulesImpl(
    private val runner: ControlRunner,
    private val conversations: ConversationStore,
    private val rules: CallRules,
) : PermissionRules {
    override suspend fun allowRules(chat: ChatAddress): List<AllowRule> {
        val key = runner.key(chat)
        return rules.inForce(key.agent, conversations.current(key).id)
    }

    override suspend fun revokeAllowRule(chat: ChatAddress, number: Int, by: ChatUser?): Revocation {
        val key = runner.key(chat)
        return rules.revoke(key.agent, conversations.current(key).id, number, by)
    }
}
