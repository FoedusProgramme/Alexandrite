package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.testkit.store.testLineage

/**
 * The turn [id] of [conversation] at [chat], changed by [block], whose actor is a guest in a message or command turn
 * and nobody in any other.
 */
public fun testTurn(
    kind: TurnKind = TurnKind.MESSAGE,
    chat: ChatAddress = testChat(),
    id: TurnId = TurnId("test-turn"),
    conversation: ConversationId = ConversationId("test"),
    block: TurnInfo.Builder.() -> Unit = {},
): TurnInfo {
    val actor = testUser(instance = chat.instance).takeIf { kind == TurnKind.MESSAGE || kind == TurnKind.COMMAND }
    return TurnInfo.builder(id, chat, conversation, kind).actor(actor).apply(block).build()
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
    val lineage = testLineage(parent, run, call)
    return TurnInfo.builder(TurnId("$run-turn"), parent.chat, ConversationId("$run"), TurnKind.DELEGATED)
        .actor(parent.actor)
        .language(parent.language)
        .agent(parent.agent)
        .lineage(lineage)
        .replyTarget(ReplyTarget.CALLER)
        .apply(block)
        .build()
}
