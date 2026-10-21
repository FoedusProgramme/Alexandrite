package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity

/** The request to open the reply of [turn], which answers [trigger], as the agent makes it for a channel. */
public fun testReplyRequest(
    turn: TurnInfo = testTurn(),
    trigger: ChannelMessageRef? = null,
    run: DelegatedRun? = null,
): ReplyRequest = ReplyRequest(turn, trigger, run)

/** The turn events that the agent shows a channel, for the channel's tests. */
public object TestEvents {
    public fun toolCallStarted(
        id: String = "call-1",
        tool: String = "test.tool",
        activity: ToolActivity = ToolActivity.EXECUTE,
        title: String = "",
        run: RunId? = null,
        agent: AgentId? = null,
    ): TurnEvent = TurnEvent.ToolCallStarted(ToolCallId(id), tool, activity, title, run, agent)

    public fun toolCallUpdated(
        id: String = "call-1",
        status: ToolCallStatus,
        content: TurnEvent.Content? = null,
        run: RunId? = null,
    ): TurnEvent = TurnEvent.ToolCallUpdated(ToolCallId(id), status, content, run)

    public fun thoughtChunk(text: String): TurnEvent = TurnEvent.ThoughtChunk(text)

    public fun usageUpdated(promptTokens: Long?, outputTokens: Long?, window: Int?): TurnEvent =
        TurnEvent.UsageUpdated(promptTokens, outputTokens, window)

    public fun compacted(tokensBefore: Long, tokensAfter: Long): TurnEvent =
        TurnEvent.Compacted(tokensBefore, tokensAfter)

    /** What a call showed its reviewer, as a [TurnEvent.ToolCallUpdated] carries it. */
    public fun preview(preview: ReviewPreview): TurnEvent.Content = TurnEvent.Content.Preview(preview)

    /** The last lines of a call's output, as a [TurnEvent.ToolCallUpdated] carries them. */
    public fun output(tail: String): TurnEvent.Content = TurnEvent.Content.Output(tail)
}
