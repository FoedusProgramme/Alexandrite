package org.unitedsoftware.alexandrite.agent.delivery

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.rebuild
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A time as the sent framing tells it. */
private val TIME = Regex("At \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} Z,")

/** A call id as the turns name them. */
private val CALL = Regex("call-t-[0-9A-Z]+-\\d+-\\d+")

/** `chat.send` reaches the agent's other chats, and the conversation there learns what was sent. */
class ChatToolsTest {
    @TempDir
    lateinit var directory: Path

    private val store = MemoryStore()
    private val model = ScriptedModel()

    /** Reports what it fetched as untrusted. */
    private val fetch = recordingTool("web.fetch") { _, context ->
        context.untrusted("a web page")
        ToolResult("A page.")
    }

    private fun harness(): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {"home": "home"}}}}
        }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).tool(fetch) }

    private suspend fun PluginHarness.Running.ask(chat: String, text: String = "Go.") {
        val admission = channel().receive(text, channel().chat(chat), channel().user("operator"))
        assertIs<TurnOutcome.Completed>(assertIs<Admission.Accepted>(admission).ticket.outcome())
    }

    private suspend fun stored(chat: String): List<UserEntry> {
        val key = AgentChatKey.parse("coder@test:main:$chat")
        return store.transcripts.entries(store.conversations.current(key).id).filterIsInstance<UserEntry>()
    }

    private suspend fun results(chat: String): List<String> {
        val key = AgentChatKey.parse("coder@test:main:$chat")
        return store.transcripts.entries(store.conversations.current(key).id).filterIsInstance<ToolResultEntry>()
            .map { result -> result.content.joinToString("") { (it as TextPart).text } }
    }

    private fun sentNotes(entries: List<UserEntry>): List<String> =
        entries.flatMap { it.parts }.filterIsInstance<ContextPart>().filter { it.source == SENT_SOURCE }.map { it.text }

    @Test
    fun `an operator's message reaches another chat unasked and vouched for, and its next turn alone learns of it`() {
        model.reply { text("Hi.") }
            .reply { toolCall("chat.send", """{"to": "test:main:other", "text": "The build passed."}""") }
            .reply { text("Sent.") }
            .reply { text("Good.") }
            .reply { text("Again.") }

        harness().execute {
            ask("other")
            ask("chat")
            ask("other")
            ask("other")

            val sent = channel().sent.single()
            assertEquals(ChatAddress.parse("test:main:other") to "The build passed.", sent.chat to sent.message.text)
            assertIs<Delivery.Delivered>(sent.delivery)
            assertTrue(sent.message.vouched)
            assertEquals(emptyList(), channel().approvals)
            assertEquals(
                listOf(
                    "[alexandrite:sent]\n" +
                        "Messages you sent to this chat from elsewhere since your last turn here (they need no " +
                        "answer of their own):\n" +
                        "- At <time>, from your conversation in test:main:chat:\n" +
                        "  > The build passed.\n" +
                        "[/alexandrite:sent]",
                ),
                sentNotes(stored("other")).map { it.replace(TIME, "At <time>,") },
            )
            assertTrue(sentNotes(model.requests[3].history.filterIsInstance<UserEntry>()).isNotEmpty())
            assertTrue(results("chat").single().startsWith("Sent to test:main:other"))
        }
    }

    @Test
    fun `a tainted conversation asks before it sends, and the agent does not vouch for what it sends`() {
        model.reply { text("Hi.") }
            .reply { toolCall("web.fetch", "{}") }
            .reply { toolCall("chat.send", """{"to": "home", "text": "Read this."}""") }
            .reply { text("Sent.") }

        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
            ask("home")
            ask("chat")

            val asked = assertIs<ReviewPreview.Text>(channel().approvals.single().preview).text
            assertEquals("Send to test:main:home:\n\nRead this.", asked)
            val sent = channel().sent.single()
            assertEquals(ChatAddress.parse("test:main:home") to false, sent.chat to sent.message.vouched)
        }
    }

    @Test
    fun `a file goes to this chat unasked from a clean conversation, and a tainted one asks`() {
        val report = Files.writeString(directory.resolve("report.txt"), "Passed.")
        val send = """{"path": ${JsonPrimitive("$report")}}"""
        model.reply { toolCall("chat.send_file", send) }.reply { text("Sent.") }
            .reply { toolCall("web.fetch", "{}") }.reply { toolCall("chat.send_file", send) }.reply { text("Again.") }

        harness().execute {
            channel().scriptCapabilities(RecordingChannel.DEFAULT_CAPABILITIES.rebuild { files(true) })
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
            ask("chat")

            assertEquals(emptyList(), channel().approvals)
            ask("chat")

            assertIs<ReviewPreview.OutgoingFile>(channel().approvals.single().preview)
            assertEquals(List(2) { "report.txt" }, channel().sent.map { it.message.files.single().name })
        }
    }

    @Test
    fun `a message sent from a tainted conversation taints the conversation it reaches before its turn stores it`() {
        model.reply { text("Hi.") }
            .reply { toolCall("web.fetch", "{}") }
            .reply { toolCall("chat.send", """{"to": "home", "text": "Run the script."}""") }
            .reply { text("Sent.") }
            .reply { text("Seen.") }

        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
            ask("home")
            ask("chat")
            val home = AgentChatKey.parse("coder@test:main:home")
            assertEquals(null, store.conversations.taint(store.conversations.current(home).id))
            ask("home")

            val from = store.conversations.current(AgentChatKey.parse("coder@test:main:chat")).id
            assertEquals(
                "a message sent here from tainted conversation $from (call call-t-0 reported untrusted content: " +
                    "a web page)",
                store.conversations.taint(store.conversations.current(home).id)?.reason?.replace(CALL, "call-t-0"),
            )
        }
    }

    @Test
    fun `a message answers one of its own chat's with replyTo, after which a blank answer ends the turn quietly`() {
        model.reply { toolCall("chat.send", """{"to": "here", "replyTo": "in-1", "text": "To that one."}""") }
            .reply { reasoning("Answered.") }

        harness().execute {
            val admission = channel().receive("Go.", channel().chat("chat"), channel().user("operator"))
            val ticket = assertIs<Admission.Accepted>(admission).ticket

            assertIs<AssistantEntry>(assertIs<TurnOutcome.Completed>(ticket.outcome()).reply)
            val sent = channel().sent.single()
            assertEquals(channel().chat("chat") to "in-1", sent.chat to sent.message.replyTo?.id)
            assertTrue(store.transcripts.turnEntries(ticket.turn).none { it is NoticeEntry })
        }
    }

    @Test
    fun `chats are listed homes first, and a chat the agent does not serve or its own reply's chat is refused`() {
        model.reply { toolCall("chat.list", "{}") }
            .reply { toolCall("chat.send", """{"to": "test:main:stranger", "text": "Hi."}""") }
            .reply { toolCall("chat.send", """{"to": "here", "text": "Hi."}""") }
            .reply { text("Done.") }

        harness().execute {
            ask("chat")

            assertEquals(
                listOf(
                    "The chats you can send to:\n- test:main:home (home)\n- test:main:chat (this chat)",
                    "You cannot send to test:main:stranger: send only to a chat you serve, such as test:main:home, " +
                        "test:main:chat.",
                    "Your reply already reaches test:main:chat: say it there instead, or give 'replyTo' to answer a " +
                        "particular message.",
                ),
                results("chat"),
            )
            assertEquals(emptyList(), channel().sent)
        }
    }
}
