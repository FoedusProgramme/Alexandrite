package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.tool.ToolContext

public fun testToolContext(conversationId: String = "test"): ToolContext = TestToolContext(conversationId)

private class TestToolContext(override val conversationId: String) : ToolContext
