package org.foedusprogramme.alexandrite.sdk.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ForwardKind
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.QuoteTrust
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import java.time.Instant

internal val work = ChannelInstanceId(ChannelType("telegram"), "work")
internal val chat = ChatAddress(work, "-100")
internal val ada = ChatUser(UserAddress(work, "1"), "Ada", "ada", isBot = false, isAdmin = true)
internal val claude = ModelRef(EndpointId("anthropic"), "claude-opus")
internal val anthropic = Dialect("anthropic")

internal fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

internal fun record(id: Long): EntryRecord =
    EntryRecord(EntryId(id), ConversationId("c1"), TurnId("t1"), Instant.parse("2026-10-07T08:30:00Z"))

internal fun user(text: String, record: EntryRecord? = null): UserEntry = UserEntry(
    record,
    listOf(TextPart(text)),
    UserOrigin.FromChat(ada, ChannelMessageRef(chat, "42"), Instant.parse("2026-10-07T08:29:59.5Z"), null, null),
)

internal fun reply(vararg parts: AssistantPart): AssistantEntry = AssistantEntry(null, parts.toList(), claude)

internal fun call(id: String, name: String = "notes.add"): ToolCallPart =
    ToolCallPart(ToolCallId(id), name, """{"text":"buy milk"}""")

internal fun result(id: String, name: String = "notes.add"): ToolResultEntry =
    ToolResultEntry(null, ToolCallId(id), name, listOf(TextPart("Saved.")), ToolOutcome.Succeeded)

/** Sample entries by the golden file that holds their JSON. */
internal val goldens: Map<String, List<TranscriptEntry>> = mapOf(
    "user" to listOf(
        UserEntry(
            record(1),
            listOf(
                ContextPart("message-context", "Sender: Ada (@ada, admin)\nTime: 2026-10-07 16:29"),
                TextPart("What is on this \"photo\"?"),
                MediaPart(MediaKind.IMAGE, "image/png", StoredMedia(MediaId("m-1")), "cat.png", 640, 480),
            ),
            UserOrigin.FromChat(
                ada,
                ChannelMessageRef(chat, "42"),
                Instant.parse("2026-10-07T08:29:59.5Z"),
                ForwardOrigin(ForwardKind.USER, "Bob", UserAddress(work, "2"), null),
                Quote.builder("Earlier answer")
                    .senderName("Athena")
                    .sender(UserAddress(work, "99"))
                    .senderIsBot(true)
                    .target(ChannelMessageRef(chat, "41"))
                    .trust(QuoteTrust(live = true, content = false))
                    .build(),
            ),
        ),
        user("Hello"),
        UserEntry(
            record(3),
            listOf(ContextPart("heartbeat", "Nobody has written for two hours.")),
            UserOrigin.Initiated("alexandrite-heartbeat", TurnKind.HEARTBEAT),
        ),
    ),
    "assistant" to listOf(
        AssistantEntry(
            record(4),
            listOf(
                ReasoningPart(
                    "The user wants a note.",
                    null,
                    ReasoningSeal(claude, anthropic, SealKind.SIGNATURE, "EqQBCkYIBx=="),
                ),
                TextPart("Saving it."),
                call("toolu_01"),
                OpaquePart(anthropic, "server_tool_use", json("""{"id":"srvtoolu_01","name":"web_search"}""")),
            ),
            claude,
            ProviderData.EMPTY.with(anthropic, json("""{"id":"msg_01"}""")),
        ),
        AssistantEntry(
            record(5),
            listOf(
                ReasoningPart(
                    null,
                    "Thought it over.",
                    ReasoningSeal(
                        ModelRef(EndpointId("openai"), "gpt-x"),
                        Dialect("openai-responses"),
                        SealKind.ENCRYPTED,
                        "gAAAAABo",
                        ProviderData.EMPTY.with(Dialect("openai-responses"), json("""{"itemId":"rs_1"}""")),
                    ),
                ),
            ),
            ModelRef(EndpointId("openai"), "gpt-x"),
        ),
        reply(
            ToolCallPart(
                ToolCallId("call-t1-0-0"),
                "notes.add",
                """{"text":""",
                ProviderData.EMPTY.with(Dialect("deepseek"), json("""{"index":0}""")),
            ),
        ),
    ),
    "tool_result" to listOf(
        ToolResultEntry(
            record(6),
            ToolCallId("toolu_01"),
            "notes.add",
            listOf(TextPart("Saved note #3.")),
            ToolOutcome.Succeeded,
        ),
        ToolResultEntry(
            null,
            ToolCallId("toolu_02"),
            "notes.add",
            listOf(TextPart("Give the note.")),
            ToolOutcome.Failed,
        ),
        ToolResultEntry(
            null,
            ToolCallId("toolu_03"),
            "shell.run",
            listOf(TextPart("Cancelled.")),
            ToolOutcome.Cancelled,
        ),
        ToolResultEntry(
            null,
            ToolCallId("toolu_04"),
            "shell.run",
            listOf(TextPart("A hook refused the call.")),
            ToolOutcome.NotRun(NotRunReason.HOOK_DENIED),
        ),
        ToolResultEntry(
            null,
            ToolCallId("toolu_05"),
            "files.write",
            listOf(TextPart("Waiting for approval #7.")),
            ToolOutcome.NotRun(NotRunReason("approval_pending"), approval = "7"),
        ),
        ToolResultEntry(
            null,
            ToolCallId("toolu_06"),
            "browser.snapshot",
            listOf(TextPart("The page:"), MediaPart(MediaKind.IMAGE, "image/jpeg", StoredMedia(MediaId("m-2")))),
            ToolOutcome.Succeeded,
        ),
    ),
    "summary" to listOf(SummaryEntry(record(7), "Ada asked for notes; three were saved.", EntryId(6))),
    "notice" to listOf(
        NoticeEntry(record(8), "The model answered with nothing.", NoticeKind.BLANK_REPLY),
        NoticeEntry(null, "A hook stopped the turn.", NoticeKind.HOOK_ABORTED),
        NoticeEntry(null, "The turn failed.", NoticeKind.FAILED),
    ),
    "unknown" to listOf(
        UnknownEntry(
            record(9),
            "approval_request",
            json(
                """{"type":"approval_request","record":{"id":9,"conversation":"c1","turn":"t1",""" +
                    """"createdAt":"2026-10-07T08:30:00Z"},"approval":"7","nested":{"list":[1,null,"x"]}}""",
            ),
        ),
        UnknownEntry(null, "poll", json("""{"type":"poll","question":"Lunch?"}""")),
        UserEntry(
            null,
            listOf(UnknownPart("sticker", json("""{"type":"sticker","pack":"cats","emoji":"😺"}"""))),
            UserOrigin.Unknown("from_agent", json("""{"type":"from_agent","agent":"helper","turn":"t9"}""")),
        ),
        reply(UnknownPart("citation", json("""{"type":"citation","url":"https://example.org"}"""))),
        ToolResultEntry(
            null,
            ToolCallId("toolu_07"),
            "files.read",
            listOf(
                MediaPart(
                    MediaKind("model"),
                    "model/gltf-binary",
                    UnknownMedia("provider_file", json("""{"type":"provider_file","fileId":"file_1"}""")),
                ),
                UnknownPart("table", json("""{"type":"table","rows":[]}""")),
            ),
            ToolOutcome.Unknown("timed_out", json("""{"type":"timed_out","after":"PT30S"}""")),
        ),
        NoticeEntry(null, "A delegated run was cut off.", NoticeKind("run_interrupted")),
    ),
)
