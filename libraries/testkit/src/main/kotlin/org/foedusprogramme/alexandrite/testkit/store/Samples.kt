package org.foedusprogramme.alexandrite.testkit.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ForwardKind
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.QuoteTrust
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.OpaquePart
import org.foedusprogramme.alexandrite.sdk.transcript.ProviderData
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.SummaryEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UnknownEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UnknownMedia
import org.foedusprogramme.alexandrite.sdk.transcript.UnknownPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.testkit.TEST_TIME
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testUser

internal val chat: ChatAddress = testChat()
internal val main: AgentChatKey = AgentChatKey(AgentId.MAIN, chat)
internal val coder: AgentChatKey = AgentChatKey(AgentId("coder"), chat)
internal val reviewer: AgentChatKey = AgentChatKey(AgentId("reviewer"), chat)
internal val model: ModelRef = ModelRef(EndpointId("anthropic"), "claude-opus")
internal val anthropic: Dialect = Dialect("anthropic")
internal val png: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

internal fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** A member's message [text] of [chat], carried by the platform message [id]. */
internal fun message(text: String, id: String = "m-$text", chat: ChatAddress = main.chat): UserEntry = UserEntry(
    null,
    listOf(ContextPart("message-context", "Sender: Member"), TextPart(text)),
    UserOrigin.FromChat(testUser(), ChannelMessageRef(chat, id), TEST_TIME, null, null),
)

internal fun mediaPart(media: StoredMedia, kind: MediaKind = MediaKind.IMAGE): MediaPart =
    MediaPart(kind, "image/png", media, "photo.png", 640, 480)

/** The lineage of the run [run] that [parent] delegates through a tool call. */
internal fun lineage(parent: TurnInfo, run: String): TurnLineage {
    val root = parent.lineage
    return TurnLineage(
        run = RunId(run),
        parentTurn = parent.id,
        parentConversation = parent.conversation,
        parentCall = ToolCallId("call"),
        rootTurn = root?.rootTurn ?: parent.id,
        rootConversation = root?.rootConversation ?: parent.conversation,
        depth = (root?.depth ?: 0) + 1,
    )
}

/** One entry of each type the codec knows and of types it does not, referring to [photo]. */
internal fun samples(photo: StoredMedia): List<TranscriptEntry> = listOf(
    UserEntry(
        null,
        listOf(ContextPart("message-context", "Sender: Member"), TextPart("What is on this photo?"), mediaPart(photo)),
        UserOrigin.FromChat(
            testUser(),
            ChannelMessageRef(chat, "42"),
            TEST_TIME,
            ForwardOrigin(ForwardKind.USER, "Someone", null, null),
            Quote.builder("Earlier answer").target(ChannelMessageRef(chat, "41")).trust(QuoteTrust.UNTRUSTED).build(),
        ),
    ),
    UserEntry(
        null,
        listOf(ContextPart("heartbeat", "Nobody has written for two hours.")),
        UserOrigin.Initiated("alexandrite-heartbeat", TurnKind.HEARTBEAT),
    ),
    AssistantEntry(
        null,
        listOf(
            ReasoningPart("The user wants a note.", null, ReasoningSeal(model, anthropic, SealKind.SIGNATURE, "Eq==")),
            TextPart("Saving it."),
            ToolCallPart(ToolCallId("toolu_01"), "notes.add", """{"text":"buy milk"}"""),
            OpaquePart(anthropic, "server_tool_use", json("""{"id":"srvtoolu_01"}""")),
        ),
        model,
        ProviderData.EMPTY.with(anthropic, json("""{"id":"msg_01"}""")),
    ),
    ToolResultEntry(null, ToolCallId("toolu_01"), "notes.add", listOf(TextPart("Saved.")), ToolOutcome.Succeeded),
    ToolResultEntry(null, ToolCallId("toolu_02"), "notes.add", listOf(TextPart("No.")), ToolOutcome.Failed),
    ToolResultEntry(null, ToolCallId("toolu_03"), "shell.run", listOf(TextPart("Stopped.")), ToolOutcome.Cancelled),
    ToolResultEntry(
        null,
        ToolCallId("toolu_04"),
        "files.write",
        listOf(TextPart("Waiting for approval #7.")),
        ToolOutcome.NotRun(NotRunReason.of("approval_pending"), approval = "7"),
    ),
    ToolResultEntry(
        null,
        ToolCallId("toolu_05"),
        "browser.snapshot",
        listOf(TextPart("The page:"), mediaPart(photo)),
        ToolOutcome.Succeeded,
    ),
    SummaryEntry(null, "The member asked for notes.", EntryId(3)),
    NoticeEntry(null, "The model answered with nothing.", NoticeKind.BLANK_REPLY),
    UnknownEntry(null, "poll", json("""{"type":"poll","question":"Lunch?","options":["a",null,1]}""")),
    UserEntry(
        null,
        listOf(UnknownPart("sticker", json("""{"type":"sticker","pack":"cats"}"""))),
        UserOrigin.Unknown("from_agent", json("""{"type":"from_agent","agent":"helper"}""")),
    ),
    ToolResultEntry(
        null,
        ToolCallId("toolu_06"),
        "files.read",
        listOf(
            MediaPart(MediaKind.of("model"), "model/gltf-binary", UnknownMedia("file", json("""{"type":"file"}"""))),
        ),
        ToolOutcome.Unknown("timed_out", json("""{"type":"timed_out"}""")),
    ),
)
