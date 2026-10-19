package org.unitedsoftware.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolNames
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

/** A gated tool call of [turn] that the agent asks an approver about, where every text is escaped for review. */
@Poko
public class ApprovalRequest(
    /** Up to 32 letters, digits, `-` and `_`, shown to approvers and logged. */
    public val id: String,
    public val turn: TurnInfo,
    public val tool: String,
    public val risk: ToolRisk,
    /** The call's arguments as canonical JSON. */
    public val arguments: String,
    public val preview: ReviewPreview?,
    /** Why a person is asked, in lower case without a closing full stop. */
    public val reason: String,
    /** What allowing such calls for good would grant, null when it may not be offered. */
    public val always: RuleSubject?,
    /** The agents from the root turn's down to the sub-agent that made the call, empty for a top-level turn's call. */
    public val chain: List<AgentId> = emptyList(),
    /** Every rule that allowing such calls for good grants, [always] first. */
    public val alwaysRules: List<RuleSubject> = listOfNotNull(always),
    /** Whether the call's output would taint the conversation, so that an approver may trust it. */
    public val taints: Boolean = false,
) {
    @Deprecated(message = "Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        id: String,
        turn: TurnInfo,
        tool: String,
        risk: ToolRisk,
        arguments: String,
        preview: ReviewPreview?,
        reason: String,
        always: RuleSubject?,
        chain: List<AgentId> = emptyList(),
    ) : this(id, turn, tool, risk, arguments, preview, reason, always, chain, listOfNotNull(always), false)

    init {
        require(ID.matches(id)) { "Malformed approval request id '$id'." }
        require(ToolNames.PATTERN.matches(tool)) { "Malformed tool name '$tool'." }
        require(alwaysRules.firstOrNull() == always) { "The rules of approval request $id start with its own." }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9_-]{1,32}")
    }
}

/** How an approval request ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ApprovalAnswer {
    /**
     * [by] allowed the call, and the calls that the request's rules cover too when [always], trusting its output when
     * [trust].
     */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Allowed(
        public val by: ChatUser,
        public val always: Boolean = false,
        public val trust: Boolean = false,
    ) : ApprovalAnswer {
        @Deprecated(message = "Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
        public constructor(by: ChatUser, always: Boolean = false) : this(by, always, false)
    }

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
