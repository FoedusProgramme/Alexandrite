package org.unitedsoftware.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.sandbox.Contained
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolNames
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

/** A gated tool call of [turn] that the agent asks an approver about, where every text is escaped for review. */
@Poko
public class ApprovalRequest private constructor(
    /** Up to 32 letters, digits, `-` and `_`, shown to approvers and logged. */
    public val id: String,
    public val turn: TurnInfo,
    public val tool: String,
    public val risk: ToolRisk,
    /** The call's arguments as canonical JSON. */
    public val arguments: String,
    public val preview: ReviewPreview?,
    /** Why a person is asked, in lower case without a closing full stop, in Alexandrite's own words. */
    public val reason: String,
    /** The agents from the root turn's down to the sub-agent that made the call, empty for a top-level turn's call. */
    public val chain: List<AgentId>,
    /** Every rule that allowing such calls for good grants, empty when it may not be offered. */
    public val alwaysRules: List<RuleSubject>,
    /** Whether the call would bring the first untrusted content into the conversation. */
    public val taints: Boolean,
    /** When and why the call's conversation first took in untrusted content, null while it took in none. */
    public val taint: ConversationTaint?,
    /** What a model or untrusted content wrote about [reason], such as the judge's doubt, null for nothing. */
    public val note: String?,
    /** The sandbox the call runs in, null when it runs on the host. */
    public val contained: Contained?,
    /** Whether the call starts as a background job. */
    public val background: Boolean,
    /** What of the call would bring the first untrusted content into the conversation, null when nothing would. */
    public val untrusted: String?,
    /**
     * What allowing the call and trusting its source trusts for good, so that the conversation stays clean, empty when
     * it may not be offered.
     */
    public val roots: List<TrustRoot>,
) {
    init {
        require(ID.matches(id)) { "Malformed approval request id '$id'." }
        require(ToolNames.PATTERN.matches(tool)) { "Malformed tool name '$tool'." }
    }

    /** The first of [alwaysRules], null when allowing such calls for good may not be offered. */
    public val always: RuleSubject? get() = alwaysRules.firstOrNull()

    public companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,32}")

        public operator fun invoke(
            id: String,
            turn: TurnInfo,
            tool: String,
            risk: ToolRisk,
            arguments: String,
            preview: ReviewPreview?,
            reason: String,
            chain: List<AgentId> = emptyList(),
            alwaysRules: List<RuleSubject> = emptyList(),
            taints: Boolean = false,
            taint: ConversationTaint? = null,
            note: String? = null,
            contained: Contained? = null,
            background: Boolean = false,
            untrusted: String? = null,
            roots: List<TrustRoot> = emptyList(),
        ): ApprovalRequest = ApprovalRequest(
            id,
            turn,
            tool,
            risk,
            arguments,
            preview,
            reason,
            chain.toList(),
            alwaysRules.toList(),
            taints,
            taint,
            note,
            contained,
            background,
            untrusted,
            roots.toList(),
        )
    }
}

/** What a person may trust for good so that content from it no longer taints a conversation. */
@Poko
public class TrustRoot private constructor(
    public val kind: TrustRootKind,
    /**
     * As people are shown it, escaped for review: the full path of a folder, a domain, a program as a command names
     * it, an MCP server's name or a person's name.
     */
    public val name: String,
    /** The person, of a [TrustRootKind.PERSON] root, else null. */
    public val person: ChatUser?,
) {
    init {
        require(name.isNotBlank()) { "A trust root shows a name." }
        require(name.none(::breaks)) { "A trust root's name is escaped for review." }
        require((kind == TrustRootKind.PERSON) == (person != null)) { "Only a person's trust root names a person." }
    }

    public companion object {
        /** The characters of a person's name that [person] keeps by default. */
        private const val MAX_NAME = 64

        public operator fun invoke(kind: TrustRootKind, name: String): TrustRoot = TrustRoot(kind, name, null)

        /** The root of [person], by default named by their display name with control characters escaped and cut. */
        public fun person(
            person: ChatUser,
            name: String = escaped(person.displayName.ifBlank { "${person.address}" }),
        ): TrustRoot = TrustRoot(TrustRootKind.PERSON, name, person)

        private fun escaped(name: String): String {
            val escaped = buildString {
                for (char in name) {
                    when {
                        char == '\\' -> append("\\\\")
                        breaks(char) -> append("\\u").append(char.code.toString(HEX).uppercase().padStart(4, '0'))
                        else -> append(char)
                    }
                }
            }
            return if (escaped.length <= MAX_NAME) escaped else "${escaped.take(MAX_NAME - 1)}…"
        }

        private const val HEX = 16
    }
}

/** Whether [char] breaks or hides a line of text that it is shown in. */
private fun breaks(char: Char): Boolean = char.isISOControl() || Character.getType(char).toByte() in BREAKING

private val BREAKING = setOf(Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR)

/** What a [TrustRoot] trusts. */
public enum class TrustRootKind {
    /** A folder and everything later written in it, outside the repositories nested in it. */
    FOLDER,

    /** The pages of a domain and its subdomains. */
    DOMAIN,

    /** The output of a program. */
    PROGRAM,

    /** The output of an MCP server's tools. */
    SERVER,

    /** What a person writes. */
    PERSON,
}

/**
 * The first untrusted content of a conversation of [turn] that nobody was asked about before it came in, offered to an
 * operator to trust, where every text is escaped for review.
 */
@Poko
public class TrustOffer private constructor(
    /** Up to 32 letters, digits, `-` and `_`. */
    public val id: String,
    public val turn: TurnInfo,
    /** Where the content came from, in lower case without a closing full stop, in Alexandrite's own words. */
    public val untrusted: String,
    /** What trusting the content trusts for good, never empty. */
    public val roots: List<TrustRoot>,
) {
    init {
        require(ID.matches(id)) { "Malformed trust offer id '$id'." }
        require(roots.isNotEmpty()) { "A trust offer offers something to trust." }
    }

    public companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,32}")

        public operator fun invoke(id: String, turn: TurnInfo, untrusted: String, roots: List<TrustRoot>): TrustOffer =
            TrustOffer(id, turn, untrusted, roots.toList())
    }
}

/** How an approval request ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ApprovalAnswer {
    /**
     * [by] allowed the call, and the calls that the request's rules cover too when [always], trusting the roots of the
     * request or the [TrustOffer] when [trust] — or, for a request that would taint and offers no roots, the call's
     * output this once.
     */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Allowed(
        public val by: ChatUser,
        public val always: Boolean = false,
        public val trust: Boolean = false,
    ) : ApprovalAnswer

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Denied(public val by: ChatUser) : ApprovalAnswer

    /** Nobody answered within the channel's limit. */
    @OptIn(InternalAlexandriteApi::class)
    public data object Expired : ApprovalAnswer

    /** The channel asked nobody, for [reason], which is in lower case without a closing full stop. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Unavailable(public val reason: String) : ApprovalAnswer
}
