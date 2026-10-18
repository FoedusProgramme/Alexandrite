package org.unitedsoftware.alexandrite.agent.delivery

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A time as the sent framing tells it. */
private val TIME = Regex("At \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} Z,")

/** `chat.send` reaches the agent's other chats, and the conversation there learns what was sent. */
class ChatToolsTest {
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
    ) { channel(admins = setOf("admin")).model(model).tool(fetch) }

    private suspend fun PluginHarness.Running.ask(chat: String, text: String = "Go.") {
        val admission = channel().receive(text, channel().chat(chat), channel().user("admin"))
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
    fun `an operator's message reaches another chat unasked, and that chat's next turn alone is told of it`() {
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
    fun `a tainted conversation asks before it sends`() {
        model.reply { text("Hi.") }
            .reply { toolCall("web.fetch", "{}") }
            .reply { toolCall("chat.send", """{"to": "home", "text": "Read this."}""") }
            .reply { text("Sent.") }

        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("admin")))
            ask("home")
            ask("chat")

            val asked = assertIs<ReviewPreview.Text>(channel().approvals.single().preview).text
            assertEquals("Send to test:main:home:\n\nRead this.", asked)
            assertEquals(ChatAddress.parse("test:main:home"), channel().sent.single().chat)
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
                    "Your reply already reaches test:main:chat: say it there instead.",
                ),
                results("chat"),
            )
            assertEquals(emptyList(), channel().sent)
        }
    }
}
