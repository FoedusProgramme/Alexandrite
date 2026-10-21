package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import java.nio.file.Path
import java.time.Instant

/**
 * What the agents trust, so that content from it does not taint a conversation: folders, trust rules and people,
 * changed without a permission check, recording who asked as `by`.
 *
 * A change that breaks a rule throws [ControlRefusedException] with a message for the user and changes nothing.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TrustControl {
    /**
     * The folders that the agent of [chat] trusts, each covering the folders below it outside the repositories nested
     * in it: those the config file declares for every agent, then those granted to the agent.
     */
    public suspend fun folders(chat: ChatAddress): List<TrustedFolder>

    /**
     * Trusts the folder [folder] (absolute) for the agent of [chat], so that its content does not taint and the project
     * instructions in it and below it load outside the repositories nested in it, and returns the trusted folder that
     * covers it, an ancestor when one covers it already.
     */
    public suspend fun trustFolder(chat: ChatAddress, folder: Path, by: ChatUser?): Path

    /**
     * Stops trusting [folder] at runtime for the agent of [chat], removing its grant whenever there is one, and tells
     * whether the config file still trusts it.
     */
    public suspend fun untrustFolder(chat: ChatAddress, folder: Path, by: ChatUser?): Revocation

    /**
     * The trust rules in force at [chat]: those the config file declares for its agent, then those granted in its
     * current conversation.
     */
    public suspend fun rules(chat: ChatAddress): List<TrustRule>

    /**
     * Trusts the output of the calls that [rule], written like a trust rule of the config file, covers in the current
     * conversation at [chat], and returns the rule.
     */
    public suspend fun trustRule(chat: ChatAddress, rule: String, by: ChatUser?): TrustRule

    /**
     * Stops trusting the calls that [rule] covers in the current conversation at [chat], removing its grant whenever
     * there is one, and tells whether the config file still declares it.
     */
    public suspend fun untrustRule(chat: ChatAddress, rule: String, by: ChatUser?): Revocation

    /** The people that the agent of [chat] was told to trust at runtime, beside those whom their channel trusts. */
    public suspend fun people(chat: ChatAddress): List<TrustedPerson>

    /**
     * Trusts what [person] writes in the chats of the agent of [chat], so that their own messages and files do not
     * taint, and returns the trusted person, who gets no authority from it.
     */
    public suspend fun trustPerson(chat: ChatAddress, person: ChatUser, by: ChatUser?): TrustedPerson

    /**
     * Stops trusting [person] for the agent of [chat], removing its grant whenever there is one, and tells whether
     * their channel still trusts them.
     */
    public suspend fun untrustPerson(chat: ChatAddress, person: ChatUser, by: ChatUser?): Revocation

    /** When and why the current conversation at [chat] first took in untrusted content, null while it took in none. */
    public suspend fun taint(chat: ChatAddress): ConversationTaint?
}

/** A trusted folder. */
@Poko
public class TrustedFolder @InternalAlexandriteApi constructor(
    public val path: Path,
    /** Whether the config file declares the folder. */
    public val declared: Boolean,
    /** When the folder was trusted at runtime, null when it was not. */
    public val trustedAt: Instant?,
    /** Who trusted the folder at runtime, null for the host or when nobody did. */
    public val trustedBy: ChatUser?,
)

/** A rule whose calls' output does not taint the conversation. */
@Poko
public class TrustRule(
    /** As the config file writes it. */
    public val rule: String,
    /** Whether the config file declares the rule. */
    public val declared: Boolean,
    /** When the rule was granted in the conversation, null for a declared one. */
    public val grantedAt: Instant?,
    /** Who granted the rule, null for a declared one or the host. */
    public val grantedBy: ChatUser?,
    /** Whether the rule trusts programs that run whatever code they are given, such as a shell. */
    public val anyCode: Boolean = false,
)

/** A person whom an agent trusts at runtime. */
@Poko
public class TrustedPerson(
    /** As their channel saw them when they were trusted. */
    public val person: ChatUser,
    public val trustedAt: Instant,
    /** Who trusted the person, null for the host. */
    public val trustedBy: ChatUser?,
)
