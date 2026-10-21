package org.unitedsoftware.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
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

    public fun toBuilder(): Builder = Builder(id, turn, tool, risk, arguments, reason)
        .preview(preview)
        .chain(chain)
        .alwaysRules(alwaysRules)
        .taint(taint)
        .note(note)
        .contained(contained)
        .background(background)
        .untrusted(untrusted)
        .roots(roots)

    public class Builder internal constructor(
        private val id: String,
        private val turn: TurnInfo,
        private val tool: String,
        private val risk: ToolRisk,
        private val arguments: String,
        private val reason: String,
    ) {
        private var preview: ReviewPreview? = null
        private var chain: List<AgentId> = emptyList()
        private var alwaysRules: List<RuleSubject> = emptyList()
        private var taint: ConversationTaint? = null
        private var note: String? = null
        private var contained: Contained? = null
        private var background: Boolean = false
        private var untrusted: String? = null
        private var roots: List<TrustRoot> = emptyList()

        public fun preview(preview: ReviewPreview?): Builder = apply { this.preview = preview }

        public fun chain(chain: List<AgentId>): Builder = apply { this.chain = chain.toList() }

        public fun alwaysRules(alwaysRules: List<RuleSubject>): Builder = apply {
            this.alwaysRules =
                alwaysRules.toList()
        }

        public fun taint(taint: ConversationTaint?): Builder = apply { this.taint = taint }

        public fun note(note: String?): Builder = apply { this.note = note }

        public fun contained(contained: Contained?): Builder = apply { this.contained = contained }

        public fun background(background: Boolean): Builder = apply { this.background = background }

        public fun untrusted(untrusted: String?): Builder = apply { this.untrusted = untrusted }

        public fun roots(roots: List<TrustRoot>): Builder = apply { this.roots = roots.toList() }

        public fun build(): ApprovalRequest = ApprovalRequest(
            id,
            turn,
            tool,
            risk,
            arguments,
            preview,
            reason,
            chain,
            alwaysRules,
            taint,
            note,
            contained,
            background,
            untrusted,
            roots,
        )
    }

    public companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,32}")

        public fun builder(
            id: String,
            turn: TurnInfo,
            tool: String,
            risk: ToolRisk,
            arguments: String,
            reason: String,
        ): Builder = Builder(id, turn, tool, risk, arguments, reason)
    }
}

public inline fun ApprovalRequest.rebuild(block: ApprovalRequest.Builder.() -> Unit): ApprovalRequest =
    toBuilder().apply(block).build()

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
@JvmInline
@Serializable
public value class TrustRootKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** A folder and everything later written in it, outside the repositories nested in it. */
        public val FOLDER: TrustRootKind = TrustRootKind("folder")

        /** The pages of a domain and its subdomains. */
        public val DOMAIN: TrustRootKind = TrustRootKind("domain")

        /** The output of a program that a command runs. */
        public val PROGRAM: TrustRootKind = TrustRootKind("program")

        /** The output of the tools that a tool source offers under one name, such as one MCP server's. */
        public val TOOL_SOURCE: TrustRootKind = TrustRootKind("tool_source")

        /** What a person writes. */
        public val PERSON: TrustRootKind = TrustRootKind("person")

        /** The values this version knows. */
        public val entries: List<TrustRootKind> = listOf(FOLDER, DOMAIN, PROGRAM, TOOL_SOURCE, PERSON)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): TrustRootKind = TrustRootKind(id)
    }
}

/**
 * What a conversation of [turn] took in or left out because nobody trusts its source, offered to an operator to trust
 * for the turn's agent, where every text is escaped for review.
 */
@Poko
public class TrustOffer private constructor(
    /** Up to 32 letters, digits, `-` and `_`. */
    public val id: String,
    public val turn: TurnInfo,
    /**
     * Where the content came from, or the folders whose project instructions were not loaded, in lower case without a
     * closing full stop, in Alexandrite's own words.
     */
    public val untrusted: String,
    /** What trusting the content trusts for good, never empty. */
    public val roots: List<TrustRoot>,
    public val kind: TrustOfferKind,
) {
    init {
        require(ID.matches(id)) { "Malformed trust offer id '$id'." }
        require(roots.isNotEmpty()) { "A trust offer offers something to trust." }
    }

    public companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,32}")

        public operator fun invoke(
            id: String,
            turn: TurnInfo,
            untrusted: String,
            roots: List<TrustRoot>,
            kind: TrustOfferKind = TrustOfferKind.TAINTED,
        ): TrustOffer = TrustOffer(id, turn, untrusted, roots.toList(), kind)
    }
}

/** What a [TrustOffer] follows. */
public enum class TrustOfferKind {
    /** The first untrusted content marked the conversation tainted: trusting its roots clears the mark. */
    TAINTED,

    /** Project instructions were not loaded: trusting their folders loads them when the agent next touches them. */
    INSTRUCTIONS,
}

/** What an [ApprovalAnswer.Allowed] trusts besides allowing. */
public enum class ApprovalTrust {
    NONE,

    /** The roots of the [ApprovalRequest] or the [TrustOffer], for good. */
    ROOTS,

    /** The call's output this once, for a request that would taint and offers no roots. */
    OUTPUT_ONCE,
}

/** How an approval request ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ApprovalAnswer {
    /** [by] allowed the call, and the calls that the request's rules cover too when [always], trusting as [trust] says. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Allowed(
        public val by: ChatUser,
        public val always: Boolean = false,
        public val trust: ApprovalTrust = ApprovalTrust.NONE,
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
