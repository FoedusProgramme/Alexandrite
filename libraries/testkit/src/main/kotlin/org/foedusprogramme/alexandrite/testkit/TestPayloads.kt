package org.foedusprogramme.alexandrite.testkit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.PromptSection
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.TurnContextItem
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryRecord
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.sdk.turn.ContextLoaded
import org.foedusprogramme.alexandrite.sdk.turn.ConversationSealed
import org.foedusprogramme.alexandrite.sdk.turn.ModelCall
import org.foedusprogramme.alexandrite.sdk.turn.ModelReply
import org.foedusprogramme.alexandrite.sdk.turn.PromptSections
import org.foedusprogramme.alexandrite.sdk.turn.ReplyDraft
import org.foedusprogramme.alexandrite.sdk.turn.ReplyPreview
import org.foedusprogramme.alexandrite.sdk.turn.ToolCallCheck
import org.foedusprogramme.alexandrite.sdk.turn.ToolCallDone
import org.foedusprogramme.alexandrite.sdk.turn.TurnCommitted
import org.foedusprogramme.alexandrite.sdk.turn.TurnContext
import org.foedusprogramme.alexandrite.sdk.turn.TurnInput
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnStart
import java.time.Instant

/** The model of a default [ScriptedModel], which the test helpers' requests and replies name. */
public val TEST_MODEL: ModelRef = ModelRef(EndpointId("scripted"), "test-model")

/** A tool that takes an object of arguments. */
public fun testToolDefinition(name: String = "test.tool", risk: ToolRisk = ToolRisk.EXEC): ToolDefinition =
    ToolDefinition(name, "A tool of the test.", JsonObject(mapOf("type" to JsonPrimitive("object"))), risk)

/** The message that a message turn's actor sent with [text], null for any other turn. */
public fun testMessageOf(turn: TurnInfo, text: String = "hello"): IncomingMessage? {
    val actor = turn.actor?.takeIf { turn.kind == TurnKind.MESSAGE } ?: return null
    return testMessage(text, turn.chat, actor)
}

public fun testRecord(id: Long, turn: TurnInfo = testTurn(), createdAt: Instant = TEST_TIME): EntryRecord =
    EntryRecord(EntryId(id), turn.conversation, turn.id, createdAt)

/** What [turn]'s actor, or a member when it has none, said in its chat. */
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

public fun testPromptSections(
    turn: TurnInfo = testTurn(),
    sections: List<PromptSection> = listOf(PromptSection("test", "You help with tests.", stable = true)),
): PromptSections = PromptSections(turn, sections)

public fun testTurnStart(
    turn: TurnInfo = testTurn(),
    tools: List<ToolDefinition> = listOf(testToolDefinition()),
    message: IncomingMessage? = testMessageOf(turn),
): TurnStart = TurnStart(turn, message, tools)

public fun testTurnInput(
    turn: TurnInfo = testTurn(),
    text: String = "hello",
    followUp: Boolean = false,
    message: IncomingMessage? = testMessageOf(turn, text),
): TurnInput = TurnInput(turn, message, text, followUp)

public fun testContextLoaded(
    turn: TurnInfo = testTurn(),
    history: List<TranscriptEntry> = listOf(testUserEntry(turn, record = testRecord(1, turn))),
): ContextLoaded = ContextLoaded(turn, history)

public fun testTurnContext(
    turn: TurnInfo = testTurn(),
    text: String = "hello",
    items: List<TurnContextItem> = emptyList(),
): TurnContext = TurnContext(turn, text, items)

public fun testModelCall(
    turn: TurnInfo = testTurn(),
    round: Int = 0,
    request: ModelRequest = testModelRequest(turn, round),
): ModelCall = ModelCall(turn, round, request)

/** The response of [TEST_MODEL] to round [round] of [turn] that [reply] makes. */
public fun testModelReply(
    turn: TurnInfo = testTurn(),
    round: Int = 0,
    reply: ScriptedReply = scriptedReply { text("Hello") },
): ModelReply = ModelReply(
    turn,
    round,
    reply.completed(TEST_MODEL, RequestIds(turn.conversation, turn.id, round), ScriptedModel.DIALECT),
)

public fun testModelReply(turn: TurnInfo, round: Int, response: ModelEvent.Completed): ModelReply =
    ModelReply(turn, round, response)

/** A check of [call] of the tool [definition], at the tool's declared risk unless [risk] is given. */
public fun testToolCallCheck(
    turn: TurnInfo = testTurn(),
    definition: ToolDefinition = testToolDefinition(),
    arguments: String = "{}",
    call: ToolCallPart = ToolCallPart(ToolCallId("test-call"), definition.name, arguments),
    risk: ToolRisk = definition.risk,
): ToolCallCheck = ToolCallCheck(turn, call, definition, risk)

public fun testToolCallDone(
    turn: TurnInfo = testTurn(),
    call: ToolCallPart = ToolCallPart(ToolCallId("test-call"), "test.tool", "{}"),
    output: String = "done",
    outcome: ToolOutcome = ToolOutcome.Succeeded,
    result: ToolResultEntry = ToolResultEntry(null, call.id, call.name, listOf(TextPart(output)), outcome),
): ToolCallDone = ToolCallDone(turn, call, result)

public fun testReplyPreview(turn: TurnInfo = testTurn(), segment: Int = 0, text: String = "Hello"): ReplyPreview =
    ReplyPreview(turn, segment, text)

/** A draft of [kind] from [turn]'s conversation. */
public fun testReplyDraft(
    turn: TurnInfo = testTurn(),
    text: String = "Hello",
    kind: MessageKind = MessageKind.REPLY,
    message: OutboundMessage = OutboundMessage.builder(text, kind).conversation(turn.conversation).build(),
    destination: ChatAddress = turn.chat,
): ReplyDraft = ReplyDraft(turn, message, destination)

/** By default, the stored user entry and reply of a turn that completed with that reply. */
public fun testTurnCommitted(
    turn: TurnInfo = testTurn(),
    entries: List<TranscriptEntry> = listOf(
        testUserEntry(turn, record = testRecord(1, turn)),
        AssistantEntry(testRecord(2, turn), listOf(TextPart("Hello")), TEST_MODEL),
    ),
    outcome: TurnOutcome = TurnOutcome.Completed(entries.filterIsInstance<AssistantEntry>().lastOrNull()),
    usage: Usage? = null,
): TurnCommitted = TurnCommitted(turn, entries, outcome, usage)

public fun testConversationSealed(
    turn: TurnInfo = testTurn(),
    successor: ConversationId = ConversationId("test-next"),
    sealed: ConversationId = turn.conversation,
): ConversationSealed = ConversationSealed(turn, sealed, successor)
