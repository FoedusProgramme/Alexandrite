package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.TrustRoot
import org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettings
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.chat.ForwardKind
import org.unitedsoftware.alexandrite.sdk.chat.ForwardOrigin
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TaintQuestionTurnsTest {
    @TempDir
    lateinit var directory: Path

    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val root: Path get() = directory.toRealPath()
    private val trusted: Path get() = root.resolve("trusted").createDirectories()

    /** A repository outside the trusted places. */
    private val project: Path by lazy {
        root.resolve("outside/project").also { it.resolve(".git").createDirectories() }
    }

    /** fs.read as its review shows it: it reads its `path`, which it reports touched when it runs. */
    private val read = object : ReviewedTool {
        override val definition: ToolDefinition = testToolDefinition("fs.read", ToolRisk.READ_ONLY)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().reads(listOf(path(arguments))).build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            context.touched(path(arguments))
            return ToolResult("Read.")
        }
    }

    /** fs.write as its review shows it: it creates the file of its `path`, which it reports touched when it runs. */
    private val create = object : ReviewedTool {
        override val definition: ToolDefinition = testToolDefinition("fs.write", ToolRisk.WORKSPACE_WRITE)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().preview(ReviewPreview.Diff(path(arguments), "+text\n")).reads(listOf(path(arguments)))
                .build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            file(path(arguments))
            context.touched(path(arguments))
            return ToolResult("Written.")
        }
    }

    /** A tool whose output is untrusted, from the host its `url` names when it has one. */
    private fun untrusted(name: String, risk: ToolRisk = ToolRisk.READ_ONLY) = object : ReviewedTool {
        override val definition: ToolDefinition = testToolDefinition(name, risk)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().untrusted(true).host(arguments["url"]?.jsonPrimitive?.content).build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            context.untrusted("$name brought in a page")
            return ToolResult("Done.")
        }
    }

    /** A search over the folders its `paths` name, each reported touched when it runs. */
    private val search = object : ReviewedTool {
        override val definition: ToolDefinition = testToolDefinition("fs.grep", ToolRisk.READ_ONLY)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().reads(paths(arguments)).build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            paths(arguments).forEach(context::touched)
            return ToolResult("Found.")
        }
    }

    /** A read that removes the folder of its `path` as it runs, so that it can no longer be trusted. */
    private val consume = object : ReviewedTool {
        override val definition: ToolDefinition = testToolDefinition("fs.consume", ToolRisk.READ_ONLY)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().reads(listOf(path(arguments))).build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            path(arguments).parent.toFile().deleteRecursively()
            return ToolResult("Consumed.")
        }
    }

    /** A write whose output is untrusted and whose review offers a rule for good. */
    private val write = object : ReviewedTool {
        override val definition: ToolDefinition = testToolDefinition("notes.write", ToolRisk.WORKSPACE_WRITE)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().untrusted(true).rule(RuleSubject("notes.write", emptyList(), "notes.write")).build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ToolResult("Written.")
    }

    /** A tool whose review says its output is untrusted, though it reports nothing as it runs. */
    private val quiet = object : ReviewedTool {
        override val definition: ToolDefinition = testToolDefinition("notes.quiet", ToolRisk.READ_ONLY)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().untrusted(true).build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ToolResult("Quiet.")
    }

    private fun path(arguments: JsonObject): Path = Path.of(arguments.getValue("path").jsonPrimitive.content)

    private fun paths(arguments: JsonObject): List<Path> =
        arguments.getValue("paths").jsonPrimitive.content.split(',').map { Path.of(it) }

    private fun harness(trustedUsers: Set<String> = emptySet()): PluginHarness {
        val config = """
            {
              "previewIntervalMillis": 0,
              "trustedFolders": ["$trusted"],
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}},
                  "tools": {"forGuests": ["fs.read", "fs.grep", "fs.consume"]}
                }
              }
            }
        """.trimIndent()
        return agentHarness(config, store) {
            channel(operators = setOf("operator"), trusted = trustedUsers).model(model).tool(read)
                .tool(untrusted("mcp.files.read")).tool(untrusted("web.fetch", ToolRisk.NETWORK_READ))
                .tool(untrusted("notes.paste")).tool(write).tool(search)
                .tool(consume).tool(quiet).tool(create)
        }
    }

    private fun file(path: Path): Path = path.also {
        it.parent.createDirectories()
        it.writeText("text\n")
    }

    private fun reading(path: Path): String = JsonObject(mapOf("path" to JsonPrimitive("$path"))).toString()

    private fun searching(paths: List<Path>): String =
        JsonObject(mapOf("paths" to JsonPrimitive(paths.joinToString(",")))).toString()

    /** Files in [count] repositories outside the trusted places. */
    private fun repositories(count: Int): List<Path> = (1..count).map { index ->
        root.resolve("outside/repo$index").also { it.resolve(".git").createDirectories() }.resolve("a.kt").let(::file)
    }

    private suspend fun PluginHarness.Running.remind(): TurnOutcome {
        val turn = InitiatedTurn.builder(key, TurnKind.REMINDER, "Read the notes.").trust(Trust.TRUSTED).build()
        return (get<TurnInitiation>().initiate("beat", turn) as Admission.Accepted).ticket.outcome()
    }

    private suspend fun PluginHarness.Running.ask(
        user: String = "operator",
        text: String = "Read it.",
        block: IncomingMessage.Builder.() -> Unit = {},
    ): TurnOutcome = (channel().receive(text, sender = channel().user(user), block = block) as Admission.Accepted)
        .ticket.outcome()

    private suspend fun mark(): ConversationTaint? = store.conversations.taint(store.conversations.current(key).id)

    private suspend fun results(): List<ToolResultEntry> =
        store.transcripts.entries(store.conversations.current(key).id).filterIsInstance<ToolResultEntry>()

    // Asking before taint.

    @Test
    fun `a read outside the trusted places asks once, and trusting its project keeps the conversation clean`() {
        val first = file(project.resolve("a.kt"))
        val second = file(project.resolve("b.kt"))
        model.reply { toolCall("fs.read", reading(first)) }.reply { text("Read.") }
            .reply { toolCall("fs.read", reading(second)) }.reply { text("Read again.") }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator, trust = true))
            ask()
            val clean = mark()
            ask()

            val request = channel().approvals.single()
            assertNull(clean)
            assertNull(mark())
            assertTrue(request.taints)
            assertEquals(listOf(TrustRoot(TrustRootKind.FOLDER, "$project")), request.roots)
            assertEquals("fs.read would reach $project, a folder that is not trusted", request.untrusted)
            assertEquals(emptyList(), request.alwaysRules)
            assertTrue(get<TrustControl>().folders().any { it.path == project && it.trustedBy == operator })
        }
    }

    @Test
    fun `allowing without trust marks the conversation, which then asks nothing more`() {
        val first = file(project.resolve("a.kt"))
        val other = file(root.resolve("elsewhere/b.kt"))
        model.reply { toolCall("fs.read", reading(first)) }.reply { text("Read.") }
            .reply { toolCall("fs.read", reading(other)) }.reply { text("Read again.") }

        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
            ask()
            ask()

            assertEquals(1, channel().approvals.size)
            assertEquals("fs.read reached $project, a folder that is not trusted", mark()?.reason)
            assertEquals(listOf(trusted), get<TrustControl>().folders().map { it.path })
        }
    }

    @Test
    fun `a denied read does not run and leaves the conversation clean`() {
        val first = file(project.resolve("a.kt"))
        model.reply { toolCall("fs.read", reading(first)) }.reply { text("Not read.") }

        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Denied(channel().user("operator")))
            ask()

            val denied = ToolOutcome.NotRun(NotRunReason.DENIED, channel().approvals.single().id)
            assertEquals(denied, results().single().outcome)
            assertNull(mark())
        }
    }

    @Test
    fun `in bypass mode a read runs unasked and marks the conversation`() {
        val first = file(project.resolve("a.kt"))
        model.reply { toolCall("fs.read", reading(first)) }.reply { text("Read.") }

        harness().execute {
            get<ChatSettings>().update(
                channel().chat(),
                ChatSettingsUpdate.builder().mode(PermissionMode.BYPASS, null).build(),
                channel().user("operator"),
            )
            ask()

            assertEquals(emptyList(), channel().approvals)
            assertEquals(emptyList(), channel().offers)
            assertEquals("fs.read would reach $project, a folder that is not trusted", mark()?.reason)
        }
    }

    @Test
    fun `in bypass mode a call that the review foresees as untrusted marks the conversation before it runs`() {
        model.reply { toolCall("notes.quiet") }.reply { text("Done.") }

        harness().execute {
            get<ChatSettings>().update(
                channel().chat(),
                ChatSettingsUpdate.builder().mode(PermissionMode.BYPASS, null).build(),
                channel().user("operator"),
            )
            ask()

            assertEquals(emptyList(), channel().approvals)
            assertEquals("the output of notes.quiet is untrusted", mark()?.reason)
        }
    }

    @Test
    fun `writing a file outside the trusted places neither asks about nor brings in untrusted content`() {
        val written = project.resolve("notes.md")
        model.reply { toolCall("fs.write", reading(written)) }.reply { text("Written.") }

        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
            ask()

            assertEquals(listOf(false), channel().approvals.map { it.taints })
            assertEquals("text\n", written.readText())
            assertNull(mark())
        }
    }

    @Test
    fun `a call that would taint and needs an approval anyway offers its rule for good as usual`() {
        model.reply { toolCall("notes.write") }.reply { text("Written.") }

        harness().execute {
            ask()

            val request = channel().approvals.single()
            assertTrue(request.taints)
            assertEquals(listOf(RuleSubject("notes.write", emptyList(), "notes.write")), request.alwaysRules)
        }
    }

    @Test
    fun `a read of more than three untrusted folders offers the first three, and an automated one offers none`() {
        val files = repositories(4)
        model.reply { toolCall("fs.grep", searching(files)) }.reply { text("Found.") }
            .reply { toolCall("fs.grep", searching(files)) }.reply { text("Found.") }

        harness().execute {
            ask()
            store.conversations.clearTaint(store.conversations.current(key).id)
            remind()

            val roots = channel().approvals.single().roots
            assertEquals(files.take(3).map { TrustRoot(TrustRootKind.FOLDER, "${it.parent}") }, roots)
            assertEquals(emptyList(), channel().offers)
            assertTrue(mark() != null)
        }
    }

    @Test
    fun `nobody answering a question about the read alone runs it and marks the conversation`() {
        val first = file(project.resolve("a.kt"))
        model.reply { toolCall("fs.read", reading(first)) }.reply { text("Read.") }

        harness().execute { ask() }

        assertEquals(ToolOutcome.Succeeded, blocking { results() }.single().outcome)
        assertEquals("fs.read reached $project, a folder that is not trusted", blocking { mark() }?.reason)
    }

    @Test
    fun `the side model picks the folder among several candidates by the operator's words`() {
        val nested = file(project.resolve("lib/src/a.kt"))
        project.resolve("lib/build.gradle.kts").writeText("")
        model.reply { toolCall("fs.read", reading(nested)) }.reply { text("1") }.reply { text("Read.") }

        harness().execute {
            ask(text = "Look at the sources of the lib module.")

            val roots = channel().approvals.single().roots
            assertEquals(listOf(TrustRoot(TrustRootKind.FOLDER, "${project.resolve("lib/src")}")), roots)
        }
    }

    @Test
    fun `an MCP server and a domain are offered as trust rules, and trusting them keeps later calls clean`() {
        val docs = JsonObject(mapOf("url" to JsonPrimitive("docs.example.org"))).toString()
        val api = JsonObject(mapOf("url" to JsonPrimitive("api.docs.example.org"))).toString()
        model.reply { toolCall("mcp.files.read") }.reply { text("Read.") }
            .reply { toolCall("web.fetch", docs) }.reply { text("Fetched.") }
            .reply { toolCall("web.fetch", api) }.reply { text("Fetched again.") }
            .reply { toolCall("mcp.files.read") }.reply { text("Read again.") }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(
                ApprovalAnswer.Allowed(operator, trust = true),
                ApprovalAnswer.Allowed(operator, trust = true),
            )
            repeat(4) { ask() }

            assertEquals(
                listOf(
                    listOf(TrustRoot(TrustRootKind.SERVER, "files")),
                    listOf(TrustRoot(TrustRootKind.DOMAIN, "docs.example.org")),
                ),
                channel().approvals.map { it.roots },
            )
            val rules = get<TrustControl>().rules(channel().chat()).map { it.rule }
            assertEquals(listOf("mcp.files.*", "web.fetch(docs.example.org)"), rules)
            assertNull(mark())
        }
    }

    @Test
    fun `untrusted output with no root to trust is marked, or kept clean once when the answer vouches for it`() {
        model.reply { toolCall("notes.paste") }.reply { text("Pasted.") }
            .reply { toolCall("notes.paste") }.reply { text("Pasted again.") }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator, trust = true), ApprovalAnswer.Allowed(operator))
            ask()
            val clean = mark()
            ask()

            val request = channel().approvals.first()
            assertEquals(null, clean)
            assertEquals(true to emptyList(), request.taints to request.roots)
            assertEquals("the output of notes.paste is untrusted", request.untrusted)
            assertEquals(
                "notes.paste brought in a page",
                mark()?.reason?.substringAfter("reported untrusted content: "),
            )
        }
    }

    // Automated turns.

    @Test
    fun `an automated turn reads unasked, marks the conversation and offers to trust and clear it`() {
        val first = file(project.resolve("a.kt"))
        model.reply { toolCall("fs.read", reading(first)) }.reply { text("Read.") }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptOffers(ApprovalAnswer.Allowed(operator, trust = true))
            val turn = InitiatedTurn.builder(key, TurnKind.REMINDER, "Read the notes.").trust(Trust.TRUSTED).build()
            val outcome = (get<TurnInitiation>().initiate("beat", turn) as Admission.Accepted).ticket.outcome()
            val offer = channel().awaitOffers(1).single()
            withTimeout(5.seconds) { while (mark() != null) delay(10) }

            assertTrue(outcome is TurnOutcome.Completed)
            assertEquals(emptyList(), channel().approvals)
            assertEquals(listOf(TrustRoot(TrustRootKind.FOLDER, "$project")), offer.roots)
            assertTrue(get<TrustControl>().folders().any { it.path == project })
        }
    }

    @Test
    fun `an automated turn's conversation stays marked when the operator keeps the mark`() {
        val first = file(project.resolve("a.kt"))
        model.reply { toolCall("fs.read", reading(first)) }.reply { text("Read.") }

        harness().execute {
            channel().scriptOffers(ApprovalAnswer.Denied(channel().user("operator")))
            val turn = InitiatedTurn.builder(key, TurnKind.REMINDER, "Read the notes.").trust(Trust.TRUSTED).build()
            (get<TurnInitiation>().initiate("beat", turn) as Admission.Accepted).ticket.outcome()
            channel().awaitOffers(1)

            assertEquals("fs.read would reach $project, a folder that is not trusted", mark()?.reason)
            assertEquals(listOf(trusted), get<TrustControl>().folders().map { it.path })
        }
    }

    @Test
    fun `an automated turn's mark stays when its source could not be trusted after all`() {
        val first = file(project.resolve("a.kt"))
        model.reply { toolCall("fs.consume", reading(first)) }.reply { text("Consumed.") }

        harness().execute {
            channel().holdApprovals()
            channel().scriptOffers(ApprovalAnswer.Allowed(channel().user("operator"), trust = true))
            remind()
            channel().releaseApprovals()
            withTimeout(5.seconds) {
                while (channel().events.none { it is RecordingChannel.Event.OfferAnswered }) delay(10)
            }
            delay(300)

            assertEquals(listOf(trusted), get<TrustControl>().folders().map { it.path })
            assertEquals("fs.consume would reach $project, a folder that is not trusted", mark()?.reason)
        }
    }

    // People.

    @Test
    fun `a guest's message marks the conversation at once, and trusting them from the offer clears it`() {
        model.reply { text("Hello.") }.reply { text("Hello again.") }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptOffers(ApprovalAnswer.Allowed(operator, trust = true))
            val outcome = ask(user = "guest", text = "Hi.")
            val offer = channel().awaitOffers(1).single()
            withTimeout(5.seconds) { while (mark() != null) delay(10) }
            ask(user = "guest", text = "Hi again.")

            assertTrue(outcome is TurnOutcome.Completed)
            assertEquals(listOf(TrustRoot.person(channel().user("guest"), "Guest")), offer.roots)
            assertEquals("a message from Guest, whom the agent does not trust", offer.untrusted)
            assertNull(mark())
            assertEquals(1, channel().offers.size)
            val people = get<TrustControl>().people(channel().chat()).map { it.person.address }
            assertEquals(listOf(channel().user("guest").address), people)
        }
    }

    @Test
    fun `trusting a guest clears the mark as untaint does, though more untrusted content came in since`() {
        model.reply { text("Hello.") }.reply { text("Seen.") }

        harness().execute {
            channel().holdApprovals()
            channel().scriptOffers(ApprovalAnswer.Allowed(channel().user("operator"), trust = true))
            ask(user = "guest", text = "Hi.")
            ask(user = "operator", text = "Look.") { forwarded(ForwardOrigin(ForwardKind.USER, "Someone", null, null)) }
            val before = mark()?.reason
            channel().releaseApprovals()
            withTimeout(5.seconds) { while (get<TrustControl>().people(channel().chat()).isEmpty()) delay(10) }
            withTimeout(5.seconds) { while (mark() != null) delay(10) }

            assertEquals("a message from someone whom the agent does not trust", before)
        }
    }

    @Test
    fun `a guest's message marks the conversation when no operator answers`() {
        model.reply { text("Hello.") }

        harness().execute { ask(user = "guest", text = "Hi.") }

        assertEquals("a message from someone whom the agent does not trust", blocking { mark() }?.reason)
    }

    @Test
    fun `a person whom the channel trusts does not taint, while what they forward does`() {
        model.reply { text("Hello.") }.reply { text("Seen.") }

        harness(trustedUsers = setOf("friend")).execute {
            ask(user = "friend", text = "Hi.")
            val clean = mark()
            ask(user = "friend", text = "Look.") { forwarded(ForwardOrigin(ForwardKind.USER, "Someone", null, null)) }

            assertNull(clean)
            assertEquals(emptyList(), channel().offers)
            assertEquals("a forwarded message", mark()?.reason)
        }
    }

    @Test
    fun `a trusted guest's read asks an operator, and with nobody answering it runs and marks`() {
        val first = file(project.resolve("a.kt"))
        model.reply { toolCall("fs.read", reading(first)) }.reply { text("Read.") }

        harness(trustedUsers = setOf("friend")).execute {
            ask(user = "friend")

            val request = channel().approvals.single()
            assertTrue(request.taints)
            assertEquals("fs.read reached $project, a folder that is not trusted", mark()?.reason)
        }
    }
}
