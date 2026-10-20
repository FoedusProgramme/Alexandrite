package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.agent.routing.LinkGroup
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.ChatLink
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinkGroup
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.Revocation

@Singleton
@Binds(ChatLinks::class)
internal class ChatLinksImpl(
    private val runner: ControlRunner,
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
    private val sealing: ConversationSealing,
) : ChatLinks {
    override suspend fun link(agent: AgentId, anchor: ChatAddress, member: ChatAddress, by: ChatUser?): ChatLinkGroup {
        val group = runner.refusing(topology.key(agent, member)) { topology.link(agent, anchor, member, by) }
        logger.info("Agent '{}' links {} into the group of {} by {}", agent, member, group.anchor, who(by))
        sealing.seal(AgentChatKey(agent, member), currentCoroutineContext()[RunningCommand]?.turn, by)
        return view(agent, group)
    }

    override suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Revocation {
        val revocation = runner.refusing(topology.key(agent, member)) { topology.unlink(agent, member) }
        when {
            revocation.declared && revocation.removed ->
                logger.info("Agent '{}' drops the link of {} by {}: the config still links it", agent, member, who(by))

            revocation.removed -> logger.info("Agent '{}' unlinks {} by {}", agent, member, who(by))
        }
        return revocation
    }

    override fun groups(agent: AgentId?): List<ChatLinkGroup> {
        val agents = if (agent == null) directory.agents.map { it.id } else listOf(agent)
        return agents.flatMap { id -> topology.groups(id).map { view(id, it) } }
    }

    /** [group] of [agent] as the host API shows it. */
    private fun view(agent: AgentId, group: LinkGroup): ChatLinkGroup {
        val links = group.members.mapNotNull { member ->
            topology.linkGrant(agent, group, member)?.let { member to ChatLink(it.at, it.by) }
        }
        return ChatLinkGroup(agent, group.anchor, group.members, group.declared, links.toMap())
    }
}

private val logger = LoggerFactory.getLogger(ChatLinksImpl::class.java)
