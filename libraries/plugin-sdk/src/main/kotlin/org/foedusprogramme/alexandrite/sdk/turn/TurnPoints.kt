package org.foedusprogramme.alexandrite.sdk.turn

import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint

/**
 * The hook points of the agent's turns.
 *
 * - A replacing hook returns the payload it got, changed through its `with…` and `plus` methods.
 * - Text a hook brings into the prompt is escaped like channel content.
 * - An untrusted turn-context item taints the turn.
 * - [RESPONSE_PREVIEW] fires for turns that reply to their chat and [RESPONSE_BEFORE] for every reply sent to a chat;
 *   every other point fires for every turn, delegated and agent-message turns included.
 */
public object TurnPoints {
    public val PROMPT_SECTIONS: InterceptorPoint<PromptSections> =
        InterceptorPoint("prompt.sections", setOf(HookEffect.REPLACE), FailurePolicy.FAIL_OPEN)

    /** Where a replacement narrows the tools, ignoring those the turn does not offer. */
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

    /** Where an abort denies the call. */
    public val TOOL_BEFORE: InterceptorPoint<ToolCallCheck> =
        InterceptorPoint("tool.before", setOf(HookEffect.ABORT), FailurePolicy.FAIL_CLOSED)

    public val TOOL_AFTER: ObserverPoint<ToolCallDone> = ObserverPoint("tool.after")

    /** Where an abort shows no preview. */
    public val RESPONSE_PREVIEW: InterceptorPoint<ReplyPreview> =
        InterceptorPoint("response.preview", setOf(HookEffect.REPLACE, HookEffect.ABORT), FailurePolicy.FAIL_CLOSED)

    public val RESPONSE_BEFORE: InterceptorPoint<ReplyDraft> =
        InterceptorPoint("response.before", setOf(HookEffect.REPLACE), FailurePolicy.FAIL_OPEN)

    public val TURN_COMMITTED: ObserverPoint<TurnCommitted> = ObserverPoint("turn.committed")

    public val CONVERSATION_SEALED: ObserverPoint<ConversationSealed> = ObserverPoint("conversation.sealed")
}
