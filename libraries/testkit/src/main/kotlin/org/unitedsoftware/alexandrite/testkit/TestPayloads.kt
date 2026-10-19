package org.unitedsoftware.alexandrite.testkit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.ContextCleared
import org.unitedsoftware.alexandrite.sdk.turn.ContextCompacted
import org.unitedsoftware.alexandrite.sdk.turn.ContextCompaction
import org.unitedsoftware.alexandrite.sdk.turn.ContextLoaded
import org.unitedsoftware.alexandrite.sdk.turn.ConversationSealed
import org.unitedsoftware.alexandrite.sdk.turn.ModelCall
import org.unitedsoftware.alexandrite.sdk.turn.ModelReply
import org.unitedsoftware.alexandrite.sdk.turn.PromptSections
import org.unitedsoftware.alexandrite.sdk.turn.ReplyDraft
import org.unitedsoftware.alexandrite.sdk.turn.ReplyPreview
import org.unitedsoftware.alexandrite.sdk.turn.ToolCallCheck
import org.unitedsoftware.alexandrite.sdk.turn.ToolCallDone
import org.unitedsoftware.alexandrite.sdk.turn.TurnCommitted
import org.unitedsoftware.alexandrite.sdk.turn.TurnContext
import org.unitedsoftware.alexandrite.sdk.turn.TurnInput
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnStart
import java.time.Instant

/** The model of a default [ScriptedModel], which the test helpers' requests and replies name. */
public val TEST_MODEL: ModelRef = ModelRef(ScriptedModel.DEFAULT_ENDPOINT, ScriptedModel.DEFAULT_MODEL)

/** A tool that takes an object of arguments, of [ToolDefinition]'s default risk unless [risk] says otherwise. */
public fun testToolDefinition(name: String = "test.tool", risk: ToolRisk = ToolRisk.EXEC): ToolDefinition =
    ToolDefinition(name, "A tool of the test.", JsonObject(mapOf("type" to JsonPrimitive("object"))), risk)

/** The message that a message turn's actor sent with [text], null for any other turn. */
public fun testMessageOf(turn: TurnInfo, text: String = "hello"): IncomingMessage? {
    val actor = turn.actor?.takeIf { turn.kind == TurnKind.MESSAGE } ?: return null
    return testMessage(text, turn.chat, actor)
}

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

/** By default, a compaction of a stored user entry and reply of [turn]'s conversation that no summary preceded. */
public fun testContextCompaction(
    turn: TurnInfo = testTurn(),
    entries: List<TranscriptEntry> = listOf(
        testUserEntry(turn, record = testRecord(1, turn)),
        AssistantEntry(testRecord(2, turn), listOf(TextPart("Hello")), TEST_MODEL),
    ),
    previous: SummaryEntry? = null,
    focus: String? = null,
): ContextCompaction = ContextCompaction(turn, entries, previous, focus)

/** A stored summary of [text] through entry [through]. */
public fun testContextCompacted(
    turn: TurnInfo = testTurn(),
    text: String = "The user said hello.",
    through: EntryId = EntryId(2),
    tokensBefore: Long = 1000,
    tokensAfter: Long = 200,
): ContextCompacted =
    ContextCompacted(turn, SummaryEntry(testRecord(3, turn), text, through), tokensBefore, tokensAfter)

public fun testContextCleared(
    turn: TurnInfo = testTurn(),
    calls: List<ToolCallPart> = listOf(ToolCallPart(ToolCallId("test-call"), "test.tool", "{}")),
    tokensFreed: Long = 500,
): ContextCleared = ContextCleared(turn, calls, tokensFreed)
