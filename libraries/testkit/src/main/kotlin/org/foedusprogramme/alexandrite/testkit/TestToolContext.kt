package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext

/** By default, a message turn of a member who is no admin, in chat `test:main:chat` and conversation `test`. */
public fun testToolContext(turn: TurnInfo = defaultTurn(), call: ToolCallId = ToolCallId("test-call")): ToolContext =
    TestToolContext(turn, call)

private class TestToolContext(override val turn: TurnInfo, override val call: ToolCallId) : ToolContext

private fun defaultTurn(): TurnInfo {
    val instance = ChannelInstanceId(ChannelType("test"), "main")
    val chat = ChatAddress(instance, "chat")
    val member = ChatUser(UserAddress(instance, "member"), "Member", null, isBot = false, isAdmin = false)
    return TurnInfo.builder(TurnId("test-turn"), chat, ConversationId("test"), TurnKind.MESSAGE).actor(member).build()
}
