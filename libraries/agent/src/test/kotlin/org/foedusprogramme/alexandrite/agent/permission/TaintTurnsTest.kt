package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.foedusprogramme.alexandrite.agent.StoreIndex
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.directory.ConversationDirectories
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.turn.Interceptor
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ForwardKind
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.QuoteTrust
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationTaint
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaintTurnsTest {
    @TempDir
    lateinit var directory: Path

    private var store = MemoryStore()
    private var model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val root: Path get() = directory.toRealPath()
    private val configFile: Path get() = root.resolve("config/alexandrite.json")
    private val persona: Path get() = configFile.resolveSibling("agents/coder/PERSONA.md")
    private val trusted: Path get() = root.resolve("trusted")
    private val outside: Path get() = root.resolve("outside")
    private val workspace: Path get() = root.resolve("data/workspaces/coder")

    /** Reports its `path` as read. */
    private val read = recordingTool("fs.read") { arguments, context ->
        context.touched(Path.of(arguments.getValue("path").jsonPrimitive.content))
        ToolResult("Read.")
    }

    private val paste = recordingTool("notes.paste") { _, context ->
        context.untrusted("a pasted page")
        ToolResult("Pasted.")
    }

    private val fetch = recordingTool("web.fetch", ToolRisk.NETWORK_READ)

    /** A harness whose store is [store], or its ports with [conversations] and [transcripts] in their place. */
    private fun harness(
        conversations: ConversationStore? = null,
        transcripts: TranscriptStore? = null,
        configure: PluginHarness.Builder.() -> Unit = {},
    ): PluginHarness {
        if (!persona.exists()) {
            persona.parent.createDirectories()
            persona.writeText("I like tea.\n")
        }
        trusted.createDirectories()
        outside.createDirectories()
        val config = """
            {
              "previewIntervalMillis": 0,
              "trustedFolders": ["$trusted"],
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
        """.trimIndent()
        val replaced = conversations != null || transcripts != null
        return agentHarness(config, store.takeUnless { replaced }) {
            if (replaced) {
                plugin(
                    StoreIndex(
                        store,
                        conversations ?: store.conversations,
                        transcripts ?: store.transcripts,
                    ),
                )
            }
            channel(admins = setOf("admin")).model(model).tool(read).tool(paste).tool(fetch).apply(configure)
            configFile(configFile).dataRoot(root.resolve("data"))
        }
    }

    private suspend fun PluginHarness.Running.ask(
        user: String = "admin",
        block: IncomingMessage.Builder.() -> Unit = {},
    ): TurnOutcome = (channel().receive("Hi", sender = channel().user(user), block = block) as Admission.Accepted)
        .ticket.outcome()

    private suspend fun mark(): ConversationTaint? = store.conversations.taint(store.conversations.current(key).id)

    private suspend fun results(): List<ToolResultEntry> =
        store.transcripts.entries(store.conversations.current(key).id).filterIsInstance<ToolResultEntry>()

    private fun reading(path: Path): String = JsonObject(mapOf("path" to JsonPrimitive("$path"))).toString()

    private fun edit(old: String, new: String): String = JsonObject(
        mapOf(
            "file" to JsonPrimitive("PERSONA.md"),
            "operation" to JsonPrimitive("replace"),
            "old" to JsonPrimitive(old),
            "new" to JsonPrimitive(new),
        ),
    ).toString()

    private fun ToolResultEntry.text(): String = content.joinToString("") { (it as TextPart).text }

    // Sources.

    @Test
    fun `a tool's report of untrusted content marks the conversation before its result is stored`() {
        val watched = Watched(store)
        model.reply { toolCall("notes.paste", id = ToolCallId("call-1")) }.reply { text("Pasted.") }

        val lines = logged {
            harness(transcripts = watched).execute { ask() }
        }

        val conversation = blocking { store.conversations.current(key).id }
        val reason = "call call-1 reported untrusted content: a pasted page"
        assertEquals(reason, blocking { mark() }?.reason)
        assertEquals(listOf(true), watched.taintedAtResults)
        assertTrue("INFO Conversation $conversation of $key is tainted: $reason" in lines, "$lines")
    }

    @Test
    fun `a third-party tool that reads the network marks the conversation, unless its call is refused`() {
        val careful =
            Interceptor(TurnPoints.TOOL_BEFORE) { HookDecision.Replace(it.withRequireApproval("Look first.")) }
        model.reply { toolCall("web.fetch") }.reply { text("Fetched.") }

        harness().execute { ask() }
        val ran = blocking { mark() }?.reason
        store = MemoryStore()
        model = ScriptedModel().reply { toolCall("web.fetch") }.reply { text("Not fetched.") }
        harness { hook(careful) }.execute { ask() }

        assertEquals("a call of web.fetch, which reads the network", ran)
        assertEquals(ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY), blocking { results() }.single().outcome)
        assertEquals(null, blocking { mark() })
    }

    @Test
    fun `a forward or a quote its channel does not vouch for marks the conversation, and a vouched quote does not`() {
        val unvouched = "a quote that its channel does not vouch for"
        fun quote(trust: QuoteTrust) = Quote.builder("Earlier.").trust(trust).build()
        val cases = listOf(
            Triple(ForwardOrigin(ForwardKind.USER, "Someone", null, null), null, "a forwarded message"),
            Triple(null, quote(QuoteTrust.UNTRUSTED), unvouched),
            Triple(null, quote(QuoteTrust(live = true, content = false)), unvouched),
            Triple(null, quote(QuoteTrust(live = true, content = true)), null),
        )

        val marks = cases.map { (origin, quoted, _) ->
            store = MemoryStore()
            model = ScriptedModel().reply { text("Seen.") }
            harness().execute {
                ask(user = "member") {
                    forwarded(origin)
                    quote(quoted)
                }
            }
            blocking { mark() }?.reason
        }

        assertEquals(cases.map { it.third }, marks)
    }

    @Test
    fun `a forwarded follow-up that joins the turn marks the conversation`() {
        val held = recordingTool("fs.glob").hold()
        model.reply { toolCall("fs.glob") }.reply { text("Done.") }

        harness { tool(held) }.execute {
            val first = channel().receive("Look.", sender = channel().user("admin")) as Admission.Accepted
            held.awaitCalls(1)
            assertEquals(null, mark())
            val second = channel().receive("This too.", sender = channel().user("admin")) {
                forwarded(ForwardOrigin(ForwardKind.CHAT, "News", null, null))
            } as Admission.Accepted
            held.release()

            first.ticket.outcome()
            assertEquals(TurnOutcome.Absorbed(first.ticket.turn), second.ticket.outcome())
            assertEquals("a forwarded message", mark()?.reason)
        }
    }

    @Test
    fun `reads in a trusted folder, the workspace or the agent's persona directory keep the conversation clean`() {
        val nested = trusted.resolve("vendored")
        nested.resolve(".git").createDirectories()
        val cases = listOf(
            listOf(trusted.resolve("src/Main.kt"), workspace.resolve("notes.md"), persona, trusted) to null,
            listOf(trusted.resolve("a.txt"), outside.resolve("b.txt")) to "$outside, a folder that is not trusted",
            listOf(nested.resolve("lib.kt")) to "$nested, a folder that is not trusted",
            listOf(configFile) to "a protected place (Alexandrite's configuration)",
        )

        val marks = cases.map { (paths, _) ->
            store = MemoryStore()
            model = ScriptedModel().reply { paths.forEach { toolCall("fs.read", reading(it)) } }.reply { text("Read.") }
            harness().execute { ask() }
            blocking { mark() }?.reason
        }

        assertEquals(cases.map { case -> case.second?.let { "fs.read reached $it" } }, marks)
    }

    @Test
    fun `a read inside the turn's own conversation directory keeps the conversation clean`() {
        lateinit var own: Path
        harness().execute {
            own = get<ConversationDirectories>().path(AgentId("coder"), store.conversations.current(key).id)
        }

        model.reply { toolCall("fs.read", reading(own.resolve("secret.txt"))) }.reply { text("Read.") }
        harness().execute { ask() }

        assertEquals(null, blocking { mark() })
    }

    // Effect.

    @Test
    fun `an operator's persona edit runs in a clean conversation, asks in a tainted one, also after a restart`() {
        model.reply { toolCall("persona.edit", edit("tea", "coffee")) }.reply { text("Noted.") }
            .reply { toolCall("fs.read", reading(outside.resolve("page.html"))) }.reply { text("Read.") }
        harness().execute {
            ask()
            ask()
        }
        model.reply { toolCall("persona.edit", edit("coffee", "cake")) }.reply { text("Not edited.") }

        harness().execute {
            ask()

            val read = "fs.read reached $outside, a folder that is not trusted"
            assertEquals(
                listOf("the conversation took in untrusted content ($read)"),
                channel().approvals.map { it.reason },
            )
        }

        val refused = ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY)
        val outcomes = blocking { results() }.map { it.outcome }
        assertEquals(listOf(ToolOutcome.Succeeded, ToolOutcome.Succeeded, refused), outcomes)
        assertEquals("I like coffee.\n", persona.readText())
    }

    @Test
    fun `a new conversation starts clean, and the one it seals keeps its mark`() {
        model.reply { toolCall("notes.paste") }.reply { text("Pasted.") }
            .reply { toolCall("persona.edit", edit("tea", "coffee")) }.reply { text("Noted.") }

        harness().execute {
            ask()
            val tainted = store.conversations.current(key).id
            get<AgentControl>().newConversation(channel().chat(), channel().user("admin"))
            ask()

            assertEquals(listOf(ToolOutcome.Succeeded), results().map { it.outcome })
            assertEquals(null, mark())
            assertTrue(store.conversations.taint(tainted) != null)
        }
        assertEquals("I like coffee.\n", persona.readText())
    }

    // Failures.

    @Test
    fun `a mark that cannot be read counts as one, and the turn says why`() {
        val unreadable = object : ConversationStore by store.conversations {
            override suspend fun taint(conversation: ConversationId): ConversationTaint? = throw IOException("Gone.")
        }
        model.reply { toolCall("persona.edit", edit("tea", "coffee")) }.reply { text("Not edited.") }
        var turn: TurnId? = null

        val lines = logged {
            harness(conversations = unreadable).execute {
                val admission = channel().receive("Hi", sender = channel().user("admin")) as Admission.Accepted
                turn = admission.ticket.turn
                admission.ticket.outcome()
                assertEquals(
                    listOf("the conversation counts as tainted, as its mark could not be read"),
                    channel().approvals.map { it.reason },
                )
            }
        }

        val conversation = blocking { store.conversations.current(key).id }
        assertTrue(
            "WARN The taint of conversation $conversation of $key could not be read, so turn $turn counts as " +
                "tainted: java.io.IOException: Gone." in lines,
            "$lines",
        )
        assertEquals("I like tea.\n", persona.readText())
    }

    @Test
    fun `a mark that cannot be stored keeps its turn tainted, and only that turn`() {
        val unwritable = object : ConversationStore by store.conversations {
            override suspend fun markTainted(conversation: ConversationId, reason: String, at: Instant): Boolean =
                throw IOException("Read-only.")
        }
        model.reply { toolCall("notes.paste") }.reply { toolCall("persona.edit", edit("tea", "cake")) }
            .reply { text("Not edited.") }
            .reply { toolCall("persona.edit", edit("tea", "coffee")) }.reply { text("Noted.") }

        val lines = logged {
            harness(conversations = unwritable).execute {
                ask()
                ask()
            }
        }

        val refused = ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY)
        val outcomes = blocking { results() }.map { it.outcome }
        assertEquals(listOf(ToolOutcome.Succeeded, refused, ToolOutcome.Succeeded), outcomes)
        assertEquals(null, blocking { mark() })
        assertTrue(lines.any { it.startsWith("WARN Conversation") && "could not be marked tainted" in it }, "$lines")
        assertEquals("I like coffee.\n", persona.readText())
    }

    // permission.status.

    @Test
    fun `the model reads in permission status why and since when the conversation is tainted`() {
        model.reply { toolCall("notes.paste", id = ToolCallId("call-1")) }.reply { toolCall("permission.status") }
            .reply { text("It is tainted.") }

        harness { zone(ZoneOffset.ofHours(8)) }.execute { ask() }

        val status = blocking { results() }.last()
        assertEquals(ToolOutcome.Succeeded, status.outcome)
        val at = blocking { mark() }!!.at
        val since = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").format(at.atOffset(ZoneOffset.ofHours(8)))
        assertEquals(
            listOf(
                "Permission status of this chat (only an administrator changes it):",
                "Mode: ask (the default).",
                "Allow rules: none.",
                "Conversation: tainted since $since +08:00 (call call-1 reported " +
                    "untrusted content: a pasted page). Until /new starts a new conversation, calls that change your " +
                    "lasting state, such as persona.edit, need an approval.",
            ),
            status.text().lines(),
        )
    }
}

/** The transcripts of [store], which note whether the conversation was marked tainted when each tool result came. */
private class Watched(private val store: MemoryStore) : TranscriptStore by store.transcripts {
    val taintedAtResults: MutableList<Boolean> = CopyOnWriteArrayList()

    override suspend fun append(turn: TurnId, entries: List<TranscriptEntry>): List<TranscriptEntry> {
        if (entries.any { it is ToolResultEntry }) {
            val conversation = checkNotNull(store.conversations.turn(turn)).conversation
            taintedAtResults += store.conversations.taint(conversation) != null
        }
        return store.transcripts.append(turn, entries)
    }
}
