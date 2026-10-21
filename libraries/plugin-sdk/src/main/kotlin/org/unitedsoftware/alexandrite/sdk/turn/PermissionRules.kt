package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import java.time.Instant

/**
 * The allow rules of a chat's agent, changed without a permission check, recording who asked as `by`.
 *
 * A change that breaks a rule throws [ControlRefusedException] with a message for the user and changes nothing.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface PermissionRules {
    /**
     * The allow rules in force at [chat]: those the config file declares for its agent, then those granted in its
     * current conversation.
     */
    public suspend fun allowRules(chat: ChatAddress): List<AllowRule>

    /**
     * Revokes the rule numbered [number] in [allowRules] at [chat] when it was granted in the conversation, and tells
     * whether the config file still allows what it covered.
     */
    public suspend fun revokeAllowRule(chat: ChatAddress, number: Int, by: ChatUser?): Revocation
}

/** A rule that lets the calls of its [subject] run without asking on an operator's turn. */
@Poko
public class AllowRule(
    /** The rule's place among the rules in force, from 1. */
    public val number: Int,
    public val subject: RuleSubject,
    /** Whether the config file declares the rule. */
    public val declared: Boolean,
    /** When the rule was granted in the conversation, null for a declared one. */
    public val grantedAt: Instant?,
    /** Who granted the rule, null for a declared one or the host. */
    public val grantedBy: ChatUser?,
)
