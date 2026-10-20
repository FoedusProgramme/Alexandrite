package org.unitedsoftware.alexandrite.testkit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import java.time.Instant

/** The model of a default [ScriptedModel], which the test helpers' requests and replies name. */
public val TEST_MODEL: ModelRef = ModelRef(ScriptedModel.DEFAULT_ENDPOINT, ScriptedModel.DEFAULT_MODEL)

/** A tool that takes an object of arguments, of [ToolDefinition]'s default risk unless [risk] says otherwise. */
public fun testToolDefinition(name: String = "test.tool", risk: ToolRisk = ToolRisk.EXEC): ToolDefinition =
    ToolDefinition(name, "A tool of the test.", JsonObject(mapOf("type" to JsonPrimitive("object"))), risk)

public fun testRecord(id: Long, turn: TurnInfo = testTurn(), createdAt: Instant = TEST_TIME): EntryRecord =
    EntryRecord(EntryId(id), turn.conversation, turn.id, createdAt)

/** What [turn]'s actor, or a guest when it has none, said in its chat. */
public fun testUserEntry(turn: TurnInfo = testTurn(), text: String = "hello", record: EntryRecord? = null): UserEntry {
    val sender = turn.actor ?: testUser(instance = turn.chat.instance)
    val origin = UserOrigin.FromChat(sender, ChannelMessageRef(turn.chat, "test-message"), TEST_TIME, null, null)
    return UserEntry(record, listOf(TextPart(text)), origin)
}

/** A request of round [round] of [turn] to [TEST_MODEL], changed by [block]. */
public fun testModelRequest(
    turn: TurnInfo = testTurn(),
    round: Int = 0,
    history: List<TranscriptEntry> = listOf(testUserEntry(turn)),
    block: ModelRequest.Builder.() -> Unit = {},
): ModelRequest =
    ModelRequest.builder(TEST_MODEL, history, 0, RequestIds(turn.conversation, turn.id, round)).apply(block).build()
