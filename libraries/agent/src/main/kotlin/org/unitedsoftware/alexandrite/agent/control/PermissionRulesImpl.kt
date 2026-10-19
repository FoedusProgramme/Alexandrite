package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.permission.CallRules
import org.unitedsoftware.alexandrite.agent.permission.TrustRules
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.turn.AllowRule
import org.unitedsoftware.alexandrite.sdk.turn.PermissionRules
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustRule

@Singleton
@Binds(PermissionRules::class)
internal class PermissionRulesImpl(
    private val runner: ControlRunner,
    private val conversations: ConversationStore,
    private val rules: CallRules,
    private val trusts: TrustRules,
) : PermissionRules {
    override suspend fun allowRules(chat: ChatAddress): List<AllowRule> {
        val key = runner.key(chat)
        return rules.inForce(key.agent, conversations.current(key).id)
    }

    override suspend fun revokeAllowRule(chat: ChatAddress, number: Int, by: ChatUser?): Revocation {
        val key = runner.key(chat)
        return rules.revoke(key.agent, conversations.current(key).id, number, by)
    }

    override suspend fun trustRules(chat: ChatAddress): List<TrustRule> {
        val key = runner.key(chat)
        return trusts.inForce(key.agent, conversations.current(key).id)
    }

    override suspend fun trust(chat: ChatAddress, rule: String, by: ChatUser?): TrustRule {
        val key = runner.key(chat)
        return runner.refusing(key) { trusts.grant(key.agent, conversations.current(key).id, rule, by) }
    }

    override suspend fun untrust(chat: ChatAddress, rule: String, by: ChatUser?): Revocation {
        val key = runner.key(chat)
        return trusts.revoke(key.agent, conversations.current(key).id, rule, by)
    }
}
