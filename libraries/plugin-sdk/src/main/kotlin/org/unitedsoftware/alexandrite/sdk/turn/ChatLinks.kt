package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import java.time.Instant

/**
 * The chats whose messages to an agent continue one conversation, changed without a permission check, recording who
 * asked as `by`.
 *
 * A change that breaks a rule throws [ControlRefusedException] with a message for the user and changes nothing.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChatLinks {
    /**
     * Links [member] into the group of [anchor] at [agent], which [anchor] starts when it is in none, and returns the
     * group.
     */
    public suspend fun link(agent: AgentId, anchor: ChatAddress, member: ChatAddress, by: ChatUser?): ChatLinkGroup

    /**
     * Takes [member] out of its group at [agent], removing its link made at runtime whenever there is one, and tells
     * whether the config file still links it.
     */
    public suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Revocation

    /** The linked groups of [agent], or of every agent when null. */
    public fun groups(agent: AgentId? = null): List<ChatLinkGroup>
}

/** Chats whose messages to [agent] continue one conversation, kept at [anchor]. */
@Poko
public class ChatLinkGroup private constructor(
    public val agent: AgentId,
    public val anchor: ChatAddress,
    public val members: List<ChatAddress>,
    /** Whether the config file declares the group. */
    public val declared: Boolean,
    /** The links of [members] made at runtime, by member. */
    public val links: Map<ChatAddress, ChatLink>,
) {
    init {
        require(members.isNotEmpty()) { "A linked group of $anchor has at least one member." }
        require(anchor !in members && members.toSet().size == members.size) {
            "A linked group holds each chat once, was $anchor with $members."
        }
        require(members.containsAll(links.keys)) { "A linked group has links of its members only, was ${links.keys}." }
    }

    public companion object {
        public operator fun invoke(
            agent: AgentId,
            anchor: ChatAddress,
            members: List<ChatAddress>,
            declared: Boolean = false,
            links: Map<ChatAddress, ChatLink> = emptyMap(),
        ): ChatLinkGroup = ChatLinkGroup(agent, anchor, members.toList(), declared, links.toMap())
    }
}

/** A link of a chat into a group made at runtime. */
@Poko
public class ChatLink @InternalAlexandriteApi constructor(
    public val linkedAt: Instant,
    /** Null for the host. */
    public val linkedBy: ChatUser?,
)
