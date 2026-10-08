package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext

/** By default, a message turn of a member who is no admin, in chat `test:main:chat` and conversation `test`. */
public fun testToolContext(turn: TurnInfo = testTurn(), call: ToolCallId = ToolCallId("test-call")): ToolContext =
    TestToolContext(turn, call)

private class TestToolContext(override val turn: TurnInfo, override val call: ToolCallId) : ToolContext
