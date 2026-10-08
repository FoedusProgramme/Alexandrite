package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage

/**
 * The turn `test-turn` of conversation `test` at [chat], changed by [block], whose actor is a member who is no admin
 * in a message or command turn and nobody in any other.
 */
public fun testTurn(
    kind: TurnKind = TurnKind.MESSAGE,
    chat: ChatAddress = testChat(),
    block: TurnInfo.Builder.() -> Unit = {},
): TurnInfo {
    val actor = testUser(instance = chat.instance).takeIf { kind == TurnKind.MESSAGE || kind == TurnKind.COMMAND }
    return TurnInfo.builder(TurnId("test-turn"), chat, ConversationId("test"), kind).actor(actor).apply(block).build()
}

/**
 * The turn `<run>-turn` in conversation `<run>` of the sub-agent run [run] that [parent] delegated through [call],
 * changed by [block], which inherits the parent's chat, actor, language and agent and replies to its caller.
 */
public fun testDelegatedTurn(
    parent: TurnInfo = testTurn(),
    run: RunId = RunId("test-run"),
    call: ToolCallId? = ToolCallId("test-call"),
    block: TurnInfo.Builder.() -> Unit = {},
): TurnInfo {
    val root = parent.lineage
    val lineage = TurnLineage(
        run = run,
        parentTurn = parent.id,
        parentConversation = parent.conversation,
        parentCall = call,
        rootTurn = root?.rootTurn ?: parent.id,
        rootConversation = root?.rootConversation ?: parent.conversation,
        depth = (root?.depth ?: 0) + 1,
    )
    return TurnInfo.builder(TurnId("$run-turn"), parent.chat, ConversationId("$run"), TurnKind.DELEGATED)
        .actor(parent.actor)
        .language(parent.language)
        .agent(parent.agent)
        .lineage(lineage)
        .replyTarget(ReplyTarget.CALLER)
        .apply(block)
        .build()
}
