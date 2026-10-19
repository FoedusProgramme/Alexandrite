package org.unitedsoftware.alexandrite.sdk.turn

import org.unitedsoftware.alexandrite.sdk.hook.FailurePolicy
import org.unitedsoftware.alexandrite.sdk.hook.HookEffect
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint

/**
 * The hook points of the agent's turns.
 *
 * - A replacing hook returns the payload it got, changed through its `with…` and `plus` methods.
 * - Text a hook brings into the prompt is escaped like channel content.
 * - An untrusted turn-context item taints the turn.
 * - [RESPONSE_PREVIEW] fires for turns that reply to their chat and [RESPONSE_BEFORE] for every reply sent to a chat;
 *   every other point fires for every turn, delegated and agent-message turns included.
 * - [CONTEXT_COMPACT] and [CONTEXT_COMPACTED] also fire for a compaction that a command asks for, with its COMMAND turn.
 */
public object TurnPoints {
    public val AGENT_SPAWN: ObserverPoint<AgentRunLifecycle> = ObserverPoint("agent.spawn")
    public val AGENT_RUN_ENDED: ObserverPoint<AgentRunLifecycle> = ObserverPoint("agent.run.ended")
    public val JOB_STARTED: ObserverPoint<JobLifecycle> = ObserverPoint("job.started")
    public val JOB_ENDED: ObserverPoint<JobLifecycle> = ObserverPoint("job.ended")
    public val TOOL_BACKGROUNDED: ObserverPoint<JobLifecycle> = ObserverPoint("tool.backgrounded")

    public val PROMPT_SECTIONS: InterceptorPoint<PromptSections> =
        InterceptorPoint("prompt.sections", setOf(HookEffect.REPLACE), FailurePolicy.FAIL_OPEN)

    /** Where a replacement narrows the tools the turn may call, ignoring those it does not offer. */
    public val TURN_START: InterceptorPoint<TurnStart> =
        InterceptorPoint("turn.start", setOf(HookEffect.REPLACE, HookEffect.ABORT), FailurePolicy.FAIL_OPEN)

    /** Where an abort refuses the input, ending a new turn or dropping a follow-up. */
    public val TURN_INPUT: InterceptorPoint<TurnInput> =
        InterceptorPoint("turn.input", setOf(HookEffect.REPLACE, HookEffect.ABORT), FailurePolicy.FAIL_CLOSED)

    public val CONTEXT_LOADED: ObserverPoint<ContextLoaded> = ObserverPoint("context.loaded")

    /** Where a replacement appends turn-context items. */
    public val CONTEXT_INJECT: InterceptorPoint<TurnContext> =
        InterceptorPoint("context.inject", setOf(HookEffect.REPLACE), FailurePolicy.FAIL_OPEN)

    public val LLM_REQUEST: InterceptorPoint<ModelCall> =
        InterceptorPoint("llm.request", setOf(HookEffect.REPLACE, HookEffect.ABORT), FailurePolicy.FAIL_OPEN)

    public val LLM_RESPONSE: InterceptorPoint<ModelReply> =
        InterceptorPoint("llm.response", setOf(HookEffect.ABORT), FailurePolicy.FAIL_OPEN)

    /**
     * Where an abort denies the call and a replacement requires approval.
     *
     * The agent reads only [ToolCallCheck.requireApproval] of a replacement and ignores its other fields.
     */
    public val TOOL_BEFORE: InterceptorPoint<ToolCallCheck> =
        InterceptorPoint("tool.before", setOf(HookEffect.REPLACE, HookEffect.ABORT), FailurePolicy.FAIL_CLOSED)

    public val TOOL_AFTER: ObserverPoint<ToolCallDone> = ObserverPoint("tool.after")

    /** Where an abort shows no preview. */
    public val RESPONSE_PREVIEW: InterceptorPoint<ReplyPreview> =
        InterceptorPoint("response.preview", setOf(HookEffect.REPLACE, HookEffect.ABORT), FailurePolicy.FAIL_CLOSED)

    public val RESPONSE_BEFORE: InterceptorPoint<ReplyDraft> =
        InterceptorPoint("response.before", setOf(HookEffect.REPLACE), FailurePolicy.FAIL_OPEN)

    public val TURN_COMMITTED: ObserverPoint<TurnCommitted> = ObserverPoint("turn.committed")

    public val CONVERSATION_SEALED: ObserverPoint<ConversationSealed> = ObserverPoint("conversation.sealed")

    /** Where a replacement gives the summary, which no model then writes, and an abort skips the compaction. */
    public val CONTEXT_COMPACT: InterceptorPoint<ContextCompaction> =
        InterceptorPoint("context.compact", setOf(HookEffect.REPLACE, HookEffect.ABORT), FailurePolicy.FAIL_OPEN)

    public val CONTEXT_COMPACTED: ObserverPoint<ContextCompacted> = ObserverPoint("context.compacted")

    public val CONTEXT_CLEARED: ObserverPoint<ContextCleared> = ObserverPoint("context.cleared")
}
