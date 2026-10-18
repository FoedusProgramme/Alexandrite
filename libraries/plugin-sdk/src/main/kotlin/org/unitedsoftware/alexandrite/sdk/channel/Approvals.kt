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
) {
    init {
        require(ID.matches(id)) { "Malformed approval request id '$id'." }
        require(ToolNames.PATTERN.matches(tool)) { "Malformed tool name '$tool'." }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9_-]{1,32}")
    }
}

/** How an approval request ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ApprovalAnswer {
    /** [by] allowed the call, and the calls that the request's rule covers too when [always]. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Allowed(public val by: ChatUser, public val always: Boolean = false) : ApprovalAnswer

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
