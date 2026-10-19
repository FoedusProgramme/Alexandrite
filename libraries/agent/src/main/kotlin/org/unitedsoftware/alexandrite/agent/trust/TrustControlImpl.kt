package org.unitedsoftware.alexandrite.agent.trust

import org.unitedsoftware.alexandrite.agent.control.ControlRunner
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.sdk.turn.TrustRule
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.sdk.turn.TrustedPerson
import java.nio.file.Path

@Singleton
@Binds(TrustControl::class)
internal class TrustControlImpl(
    private val runner: ControlRunner,
    private val conversations: ConversationStore,
    private val trust: TrustSources,
    private val taints: ConversationTaints,
) : TrustControl {
    override fun folders(): List<TrustedFolder> = trust.folders.folders()

    override suspend fun trustFolder(folder: Path, by: ChatUser?): Path =
        runner.refusing(null) { trust.folders.trust(folder, by) }

    override suspend fun untrustFolder(folder: Path, by: ChatUser?): Revocation =
        runner.refusing(null) { trust.folders.untrust(folder, by) }

    override suspend fun rules(chat: ChatAddress): List<TrustRule> {
        val key = runner.key(chat)
        return trust.rules.inForce(key.agent, conversations.current(key).id)
    }

    override suspend fun trustRule(chat: ChatAddress, rule: String, by: ChatUser?): TrustRule {
        val key = runner.key(chat)
        return runner.refusing(key) { trust.rules.grant(key.agent, conversations.current(key).id, rule, by) }
    }

    override suspend fun untrustRule(chat: ChatAddress, rule: String, by: ChatUser?): Revocation {
        val key = runner.key(chat)
        return trust.rules.revoke(key.agent, conversations.current(key).id, rule, by)
    }

    override suspend fun people(chat: ChatAddress): List<TrustedPerson> = trust.people.list(runner.key(chat).agent)

    override suspend fun trustPerson(chat: ChatAddress, person: ChatUser, by: ChatUser?): TrustedPerson {
        val key = runner.key(chat)
        return runner.refusing(key) { trust.people.trust(key.agent, person, by) }
    }

    override suspend fun untrustPerson(chat: ChatAddress, person: ChatUser, by: ChatUser?): Revocation =
        trust.people.untrust(runner.key(chat).agent, person, by)

    override suspend fun taint(chat: ChatAddress): ConversationTaint? {
        val key = runner.key(chat)
        return taints.mark(conversations.current(key).id)
    }
}
