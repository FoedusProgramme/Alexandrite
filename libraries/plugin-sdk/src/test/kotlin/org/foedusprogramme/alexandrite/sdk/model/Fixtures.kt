package org.foedusprogramme.alexandrite.sdk.model

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.claude
import org.foedusprogramme.alexandrite.sdk.transcript.user

internal val ids = RequestIds(ConversationId("c1"), TurnId("t1"), 0)

internal fun tool(name: String): ToolDefinition =
    ToolDefinition(name, "Does $name.", JsonObject(emptyMap()), ToolRisk.AGENT_STATE)

internal fun request(
    history: List<TranscriptEntry> = listOf(user("Hello")),
    turnStart: Int = 0,
): ModelRequest.Builder = ModelRequest.builder(claude, history, turnStart, ids)
