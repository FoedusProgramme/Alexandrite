@file:OptIn(ExperimentalCompilerApi::class)

package org.unitedsoftware.alexandrite.testkit

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.turn.Mandate
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicApiTest {
    @TempDir
    lateinit var workingDir: File

    /** A third-party plugin's test that uses every double, compiled without the internal API. */
    private val sample = """
        import kotlinx.coroutines.flow.MutableStateFlow
        import kotlinx.coroutines.flow.toList
        import kotlinx.serialization.json.JsonObject
        import org.unitedsoftware.alexandrite.runtime.TextCatalog
        import org.unitedsoftware.alexandrite.sdk.agent.*
        import org.unitedsoftware.alexandrite.sdk.channel.*
        import org.unitedsoftware.alexandrite.sdk.chat.*
        import org.unitedsoftware.alexandrite.sdk.di.key
        import org.unitedsoftware.alexandrite.sdk.hook.ObserverHook
        import org.unitedsoftware.alexandrite.sdk.model.*
        import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
        import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
        import org.unitedsoftware.alexandrite.sdk.store.collection
        import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
        import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
        import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
        import org.unitedsoftware.alexandrite.sdk.tool.Tool
        import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
        import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
        import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
        import org.unitedsoftware.alexandrite.sdk.transcript.*
        import org.unitedsoftware.alexandrite.sdk.turn.*
        import org.unitedsoftware.alexandrite.sdk.web.WebQuery
        import org.unitedsoftware.alexandrite.testkit.*
        import java.nio.file.Path
        import java.time.ZoneOffset
        import kotlin.time.Duration.Companion.seconds

        suspend fun models(): List<Any?> {
            val info = ScriptedModel.modelInfo("m")
            val scripted =
                ScriptedModel(EndpointId("main"), listOf(info), TurnContextMode.TRANSIENT, TurnContextMode.NOT_SUPPORTED)
            scripted.reply(RequestMatch.round(0)) {
                reasoning("Hm.", summary = "short", seal = "sig", sealKind = SealKind.SIGNATURE)
                text("Sav", "ing.")
                toolCall("notes.add", "{}", ToolCallId("c1"))
                opaque("citation", JsonObject(emptyMap()))
                usage(Usage.builder().build())
                finish(FinishKind.TOOL_USE)
                warning("option", "ignored")
                pause { }
            }
            val builder: ScriptedReply.Builder = ScriptedReply.builder().text("Hi")
            val reply: ScriptedReply = builder.fail(ModelErrorKind.TIMEOUT).build()
            scripted.reply(reply, RequestMatch.turn(TurnId("t")))
            scripted.reply(scriptedReply { hang() }, RequestMatch("any") { true })
            scripted.fail(ModelError.builder(ModelErrorKind.RATE_LIMITED, "Slow.").build(), RequestMatch.ANY)
            scripted.fail(ModelErrorKind.OVERLOADED, "Busy.")
            scriptedReply { fail(ModelError.builder(ModelErrorKind.SERVER_ERROR, "Gone.").build()) }
            scriptedReply { stop() }
            scriptedReply { crash(IllegalStateException("Unreadable.")) }
            val request = testModelRequest(testTurn(), 0, listOf(testUserEntry(testTurn(), "hi", testRecord(1)))) {
                model(scripted.ref)
            }
            val events: List<ModelEvent> = scripted.stream(request).toList()
            scripted.assertFinished()
            return listOf(
                events, scripted.requests, scripted.remaining, scripted.problems, scripted.awaitRequests(1),
                scripted.models(), scripted.turnContextMode("m", ModelOptions.DEFAULT, Trust.TRUSTED), scripted.id,
                runCatching { scripted.webSearch("m", WebQuery("query", 1)) }.isFailure,
                ScriptedModel.DIALECT, ScriptedModel.DEFAULT_ENDPOINT, ScriptedModel.DEFAULT_MODEL,
                TEST_MODEL,
            )
        }

        suspend fun channels(submitter: RecordingTurnSubmitter): List<Any?> {
            val channel = RecordingChannel(TEST_INSTANCE, setOf("ada"), RecordingChannel.DEFAULT_PART_LENGTH, submitter)
            channel.scriptCapabilities(RecordingChannel.DEFAULT_CAPABILITIES, channel.chat("c", "t"))
            channel.scriptDeliveries(Delivery.NotDelivered(DeliveryFailure.CHAT_GONE), chat = null)
            val message: IncomingMessage = channel.message("hi", channel.chat(), channel.user("ada")) { text("hey") }
            val command: CommandInvocation = channel.command("new", "now", channel.chat(), channel.user())
            val admissions = listOf(
                channel.receive("hi"),
                channel.receiveCommand("new"),
                channel.submit(Submission.Message(message)),
            )
            val sink = channel.openReply(ReplyRequest(testTurn(), null))
            sink.preview(0, "Hel")
            sink.startSegment(1)
            sink.complete(OutboundMessage.builder("Hello", MessageKind.REPLY).build())
            channel.send(channel.chat(), OutboundMessage.builder("Note", MessageKind.NOTICE).build())
            val recorded: RecordedReply = channel.awaitReply(TurnId("test-turn")).awaitEnd()
            channel.defaultApproval = RecordingChannel.NO_APPROVER
            channel.scriptApprovals(ApprovalAnswer.Expired)
            channel.holdApprovals()
            channel.releaseApprovals()
            val request = ApprovalRequest("a1", testTurn(), "notes.add", ToolRisk.EXEC, "{}", null, "a test")
            val answer: ApprovalAnswer = channel.askApproval(request)
            val approvals: List<ApprovalRequest> = channel.awaitApprovals(1) + channel.approvals
            channel.defaultOffer = RecordingChannel.NO_APPROVER
            channel.scriptOffers(ApprovalAnswer.Expired)
            val offer = org.unitedsoftware.alexandrite.sdk.channel.TrustOffer(
                "o1",
                testTurn(),
                "a test",
                listOf(org.unitedsoftware.alexandrite.sdk.channel.TrustRoot.person(channel.user())),
            )
            val offered: List<Any?> = listOf(channel.offerTrust(offer), channel.awaitOffers(1), channel.offers)
            channel.guestsMayRun = false
            val mayRun: Boolean = channel.mayRun(channel.chat(), channel.user(), "new", withArguments = false)
            val isOperator: Boolean = channel.isOperator(channel.chat(), channel.user().address)
            val preview: RecordedReply.Preview = recorded.previews.first()
            val event = channel.awaitEvent { it is RecordingChannel.Event.Completed }
            val described = when (event) {
                is RecordingChannel.Event.Opened -> event.request
                is RecordingChannel.Event.Previewed -> listOf(event.turn, event.segment, event.text)
                is RecordingChannel.Event.Completed -> listOf(event.turn, event.message, event.delivery)
                is RecordingChannel.Event.Abandoned -> listOf(event.turn, event.end)
                is RecordingChannel.Event.Sent -> listOf(event.chat, event.message, event.delivery)
                is RecordingChannel.Event.Received -> event.submission
                is RecordingChannel.Event.ApprovalAsked -> event.request
                is RecordingChannel.Event.ApprovalAnswered -> listOf(event.id, event.answer)
                is RecordingChannel.Event.ApprovalWithdrawn -> event.id
                is RecordingChannel.Event.TrustOffered -> event.offer
                is RecordingChannel.Event.OfferAnswered -> listOf(event.id, event.answer)
                is RecordingChannel.Event.Activity -> listOf(event.turn, event.event)
                else -> null
            }
            return listOf(
                command, admissions, described, answer, approvals, offered, mayRun, isOperator, preview.segment,
                preview.text,
                recorded.request,
                recorded.turn,
                recorded.segments, recorded.startedSegments, recorded.completed, recorded.delivery, recorded.abandoned,
                recorded.ended, recorded.activity,
                channel.instance, channel.events, channel.replies, channel.reply(TurnId("test-turn")), channel.sent,
                channel.violations, channel.capabilities(channel.chat()),
                channel.partsNeeded(channel.chat(), "x", Markup.PLAIN), channel.title(channel.chat()),
                RecordingChannel.STOPPED,
            )
        }

        suspend fun turns(): List<Any?> {
            val submitter = RecordingTurnSubmitter(TurnOutcome.Cancelled)
            submitter.refuseNext(RefusalReason.QUEUE_FULL, 20)
            submitter.submit(Submission.Command(testCommand("new", "", testChat(), testUser(), null)))
            val initiator = RecordingTurnInitiator("probe", null)
            initiator.refuseNext(RefusalReason.NO_AGENT)
            val initiated = InitiatedTurn.builder(testChat("c", null, TEST_INSTANCE), TurnKind.REMINDER, "Go.").build()
            initiator.initiate(initiated)
            initiator.revoke(Mandate("m-test"))
            val first: RecordingTurnInitiator.Initiated = initiator.awaitInitiated(1).first()
            val ticket = TestTurnTicket(TurnId("t"))
            ticket.complete(TurnOutcome.Completed(null))
            ticket.cancel()
            val context = TestCommandContext(testTurn(TurnKind.COMMAND), null)
            context.scriptDeliveries(Delivery.Delivered(emptyList()))
            context.reply("Done.")
            val invoked = testCommandContext(testCommand("new"))
            val control = RecordingAgent(AgentId.MAIN)
            control.statuses = emptyList()
            control.jobStatuses = emptyList()
            control.compaction = CompactionReport(1, 2, 1)
            control.scriptContext(testChat(), ContextStatus(ConversationId("c"), null, null, null, null, null, null, null, 0))
            control.scriptUsage(testChat(), UsageReport(testChat(), emptyList()))
            control.scriptAgent(AgentId("coder"), "Coder", "Writes code.", TEST_MODEL, null, LanguageTag("en"))
            control.scriptModels(EndpointId("scripted"), listOf(ScriptedModel.modelInfo()))
            control.scriptProfile(AgentProfile.builder(AgentId("reviewer"), "Reviewer", "Reviews code.", "Be strict.").build())
            control.refuseNext("No agent serves the chat.")
            control.turns.cancel(testChat(), null)
            control.turns.cancelTurn(TurnId("t"), null)
            control.turns.cancelRun(RunId("r"), true, null)
            control.turns.stopJob(testChat(), RunId("j"), null)
            control.conversations.newConversation(testChat(), null)
            control.conversations.compact(testChat(), "the tests", null)
            control.taints[testChat()] =
                org.unitedsoftware.alexandrite.sdk.store.ConversationTaint(java.time.Instant.EPOCH, "a quote")
            control.conversations.untaint(testChat(), null)
            control.heartbeatTaints[testChat()] =
                org.unitedsoftware.alexandrite.sdk.store.ConversationTaint(java.time.Instant.EPOCH, "a web page")
            control.conversations.untaintHeartbeat(testChat(), null)
            control.anyCodeRules += "command.exec(sh)"
            control.settings.update(testChat(), ChatSettingsUpdate.builder().build(), null)
            control.links.link(AgentId.MAIN, testChat(), testChat("other"), null)
            control.links.unlink(AgentId.MAIN, testChat("other"), null)
            control.trust.trustFolder(Path.of("/srv/repo"), null)
            control.trust.untrustFolder(Path.of("/srv/repo"), null)
            control.trust.trustPerson(testChat(), testUser("bob"), null)
            control.trust.untrustPerson(testChat(), testUser("bob"), null)
            control.declareAllowRule(RuleSubject("notes.add", emptyList(), "notes.add"))
            control.grantAllowRule(testChat(), RuleSubject("command.exec", listOf("make"), "make"), null)
            control.rules.revokeAllowRule(testChat(), 2, null)
            control.declareTrust("web.fetch")
            control.trust.trustRule(testChat(), "command.exec(git)", null)
            control.trust.untrustRule(testChat(), "command.exec(git)", null)
            val call = when (val recorded = control.calls.first()) {
                is RecordingAgent.Call.Cancel -> listOf(recorded.chat, recorded.by)
                is RecordingAgent.Call.CancelTurn -> listOf(recorded.turn, recorded.by)
                is RecordingAgent.Call.CancelRun -> listOf(recorded.run, recorded.tree, recorded.by)
                is RecordingAgent.Call.StopJob -> listOf(recorded.chat, recorded.job, recorded.by)
                is RecordingAgent.Call.NewConversation ->
                    listOf(recorded.chat, recorded.by, recorded.conversation)
                is RecordingAgent.Call.Compact -> listOf(recorded.chat, recorded.focus, recorded.by)
                is RecordingAgent.Call.Untaint -> listOf(recorded.chat, recorded.by)
                is RecordingAgent.Call.UntaintHeartbeat -> listOf(recorded.chat, recorded.by)
                is RecordingAgent.Call.UpdateSettings -> listOf(recorded.chat, recorded.update, recorded.by)
                is RecordingAgent.Call.Link -> listOf(recorded.agent, recorded.anchor, recorded.member)
                is RecordingAgent.Call.Unlink -> listOf(recorded.agent, recorded.member, recorded.by)
                is RecordingAgent.Call.TrustFolder -> listOf(recorded.folder, recorded.by)
                is RecordingAgent.Call.UntrustFolder -> listOf(recorded.folder, recorded.by)
                is RecordingAgent.Call.RevokeAllowRule -> listOf(recorded.chat, recorded.number, recorded.by)
                is RecordingAgent.Call.Trust -> listOf(recorded.chat, recorded.rule, recorded.by)
                is RecordingAgent.Call.Untrust -> listOf(recorded.chat, recorded.rule, recorded.by)
                is RecordingAgent.Call.TrustPerson -> listOf(recorded.chat, recorded.person, recorded.by)
                is RecordingAgent.Call.UntrustPerson -> listOf(recorded.chat, recorded.person, recorded.by)
                else -> emptyList()
            }
            val states = TestChatStates()
            return listOf(
                submitter.submissions, submitter.results, submitter.tickets, submitter.awaitSubmissions(1),
                initiator.initiated, initiator.results, initiator.tickets, first.plugin, first.turn, ticket.turn,
                ticket.cancelled, ticket.ended, ticket.outcome(), context.turn, context.replies,
                context.repliesForModel, invoked, call,
                control.turns.turns(null), control.turns.jobs(testChat()), control.settings.snapshot(testChat()),
                control.links.groups(), control.usage.report(testChat()),
                control.conversations.context(testChat()), control.compaction,
                control.trust.folders(),
                control.rules.allowRules(testChat()),
                control.trust.rules(testChat()),
                control.trust.people(testChat()),
                control.trust.taint(testChat()),
                control.catalog.agents(TEST_INSTANCE), control.catalog.profiles(),
                control.catalog.endpoints(),
                control.catalog.models(EndpointId("scripted")),
                states.of("probe"),
                testChatStates("probe"),
                TestRecords(MemoryStore().records).of("probe"),
                TestRecords(MemoryStore()).store,
                TestChatStates(MemoryStore().chatStates).store,
                testRecords("probe").collection<String>("notes").list(),
                control.trust.folders().map { listOf(it.path, it.declared, it.trustedAt, it.trustedBy) },
                control.declareFolder(Path.of("/srv/config")),
                control.declareHome(AgentId.MAIN, testChat("home")),
                control.declareLink(AgentId.MAIN, testChat("anchor"), listOf(testChat("member"))),
                testToolContext(testDelegatedTurn(testTurn(), RunId("r"), ToolCallId("c")) { }, ToolCallId("c")),
                testMessage("hi", testChat(), testUser("ada", true, TEST_INSTANCE), "m1") { }, TEST_TIME,
            )
        }

        suspend fun tools(): List<Any?> {
            val tool: RecordingTool = recordingTool("notes.find", ToolRisk.READ_ONLY) { arguments, context ->
                ToolResult("${'$'}{arguments.size} in ${'$'}{context.call}")
            }
            tool.hold().release()
            val result = tool.execute(JsonObject(emptyMap()), testToolContext())
            val call: RecordingTool.Call = tool.awaitCalls(1).first()
            val floor: HardFloor =
                testHardFloor(listOf(Path.of("/srv/secret")), Path.of("/home/user"), asked = listOf(Path.of("/srv/ask")))
            val checked = when (val check = floor.checkRead(Path.of("/srv/secret/key"))) {
                is FloorCheck.Allowed -> check.canonical
                is FloorCheck.Ask -> check.reason
                is FloorCheck.Denied -> check.reason
            }
            val cleared = org.unitedsoftware.alexandrite.sdk.permission.Clearance(
                org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind.MODE,
            )
            val context: TestToolContext = testToolContext(workspace = Path.of("/srv/work"), clearance = cleared)
            context.touched(Path.of("/srv/work/notes.md"))
            context.untrusted("a web page")
            val mandate = context.mandate()
            val background = context.background()
            testToolContext().end()
            val defined = recordingTool(testToolDefinition("notes.list", ToolRisk.READ_ONLY)) { _, _ -> ToolResult("") }
            val quotaRefusal = context.conversationQuotaRefusal(Path.of("/srv/conversation/notes.md"), 10L)
            val environment = RecordingEnvironment("seatbelt", available = true)
            val launched = environment.launch(
                ProcessBuilder("true"),
                org.unitedsoftware.alexandrite.sdk.sandbox.Boundary(listOf(Path.of("/srv/work"))),
            )
            val launch: RecordingEnvironment.Launch = environment.launches.single()
            return listOf(
                environment.available(), launched, launch.command, launch.boundary, testToolContext(sandbox = null).sandbox,
                mandate,
                result, tool.definition, tool.calls, tool.cancelled, call.arguments, call.turn, call.call, checked,
                context.turn, context.call, context.model, context.workspace, context.touchedPaths, context.openWorkspace(), defined,
                context.clearance, context.untrustedReasons, context.tainted, context.conversationDirectory,
                context.openConversationDirectory(), quotaRefusal, background.toString(),
            )
        }

        suspend fun harness(index: PluginIndex, other: PluginIndex, root: Path): List<Any> {
            val model = ScriptedModel()
            val store = MemoryStore(java.time.Clock.systemUTC())
            val hook = object : ObserverHook<TurnCommitted> {
                override val point = TurnPoints.TURN_COMMITTED

                override suspend fun observe(payload: TurnCommitted) {}
            }
            val handler = object : CommandHandler {
                override val commands = listOf(CommandSpec.builder("ping", "Answers pong.").build())

                override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {}
            }
            val builder: PluginHarness.Builder = PluginHarness.builder(index)
            val harness = builder
                .plugin(other)
                .tool(recordingTool())
                .toolSource(
                    object : ToolSource {
                        override val namespace = "remote"
                        override val tools = MutableStateFlow(listOf<Tool>(recordingTool("remote.ping")))
                    },
                )
                .hook(hook)
                .executionEnvironment(RecordingEnvironment())
                .commandHandler(handler)
                .config("{}")
                .config(JsonObject(emptyMap()))
                .config(other, "{}")
                .config(other, JsonObject(emptyMap()))
                .dataRoot(root)
                .configFile(root.resolve("alexandrite.json"))
                .keptConfigFile(root.resolve("alexandrite.json"))
                .protect(root.resolve("logs"))
                .zone(ZoneOffset.UTC)
                .language(LanguageTag("zh-CN"))
                .texts(TextCatalog.EMPTY)
                .texts(kotlinx.coroutines.flow.MutableStateFlow(TextCatalog.EMPTY))
                .shutdownGrace(1.seconds)
                .agentPlaces { org.unitedsoftware.alexandrite.runtime.ConventionalAgentPlaces(it) }
                .inspectTermination()
                .model(model)
                .watch(ScriptedModel(EndpointId("decorated")))
                .channel("main", "test", setOf("ada"), 100)
                .turnSubmitter(RecordingTurnSubmitter())
                .turnInitiator(RecordingTurnInitiator())
                .agent(RecordingAgent())
                .store(store)
                .chatStates(TestChatStates(store))
                .records(TestRecords(store))
                .hardFloor(testHardFloor())
                .hardFloor()
                .environment(mapOf("TOKEN" to "not a secret"))
                .build()
            val seen = mutableListOf<Any>()
            val block: suspend PluginHarness.Running.() -> Unit = {
                seen.addAll(listOf(dataDir, cacheDir, get<ChannelDirectory>(), getAll<ModelProvider>()))
                seen.add(model("scripted"))
                seen.add(readConfig())
                seen.addAll(listOf(channel("main", "test"), channel(TEST_INSTANCE), get(key<ChannelDirectory>())))
                seen.addAll(getAll(key<ModelProvider>()))
                seen.add(testHardFloor(get<HostPaths>(), listOf(root.resolve("secret")), Path.of("/home/user")))
                stop()
            }
            seen.addAll(listOf(store.conversations, store.transcripts, store.media, store.chatStates, store.records))
            return seen + harness.run(block)
        }

        fun texts(): List<Any> {
            val catalog = TextCatalog.builder().text("greeter", LanguageTag("zh-CN"), "greeting", "你好").build()
            val texts = testTexts("greeter", LanguageTag("en"), catalog, ClassLoader.getSystemClassLoader())
            return assertTextsComplete("greeter") + assertTextsComplete("greeter", catalog, ClassLoader.getSystemClassLoader()) +
                assertTextsComplete("greeter", ClassLoader.getSystemClassLoader()) + texts.text("greeting", null, "name" to 1)
        }

        suspend fun stores(backend: PluginIndex): List<String> {
            val checks: List<StoreCheck> = STORE_CONTRACT
            for (check in checks) check.run { PluginHarness.builder(backend) }
            return checks.map { it.name } + TestChatStates().store.toString()
        }

        suspend fun providers(endpoint: ModelEndpoint): List<Any?> {
            val server = FakeModelServer()
            val stream: FakeResponse = fakeResponse { event("{}", type = "message"); comment("ping"); pause(1.seconds) }
            val error = FakeResponse.builder(429).header("retry-after", "1").body("{}").build()
            server.enqueue(stream.dropped(), "/chat").enqueue(stream.hanging()).enqueue(error, path = null)
            val request: RecordedRequest = server.awaitRequests(1).first()
            val seen = listOf(
                server.baseUrl, server.requests, server.problems, error.status, request.method, request.path,
                request.headers, request.header("accept"), request.body, request.json(), request.awaitClosed(1.seconds),
            )
            server.assertFinished()
            server.close()
            val fixture = object : ProviderFixture {
                override val model: String = "m"
                override val plainModel: String? = null
                override fun endpoint(server: FakeModelServer): ModelEndpoint = endpoint
                override fun models(server: FakeModelServer) {}
                override fun text(chunks: List<String>): FakeResponse = stream
                override fun textStart(chunk: String): FakeResponse = stream
                override fun reasoning(reasoning: List<String>, text: List<String>): FakeResponse = stream
                override fun toolCalls(calls: List<ScriptedToolCall>): FakeResponse =
                    stream.also { calls.map { listOf(it.id, it.wireName, it.arguments) } }
                override fun finish(text: String, raw: String): FakeResponse = stream
                override fun blankAtOutputLimit(): FakeResponse = stream
                override fun usage(text: String, usage: Usage): FakeResponse = stream
                override fun rateLimited(retryAfterSeconds: Int, requestId: String): FakeResponse = error
                override fun serverError(): FakeResponse = error
                override fun toolResults(request: RecordedRequest): List<Pair<String, String>> = emptyList()
            }
            val checks: List<ProviderCheck> = PROVIDER_CONTRACT
            for (check in checks) check.run(fixture)
            assertStreamContract(testModelRequest(), emptyList(), null)
            return seen + checks.map { it.name } + ScriptedToolCall("c", "notes-add", listOf("{}"))
        }
    """.trimIndent()

    private fun errors(vararg sources: Pair<String, String>): List<String> = KotlinCompilation().apply {
        workingDir = this@PublicApiTest.workingDir
        this.sources = sources.map { (name, text) -> SourceFile.kotlin(name, text) }
        inheritClassPath = true
        jvmTarget = "21"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile().messages.lines().filter { it.startsWith("e: ") }

    @Test
    fun `a plugin's test uses every double without the internal API`() {
        assertEquals(emptyList(), errors("Sample.kt" to sample))
    }

    @Test
    fun `a test without the internal API cannot call what leaks an internal type in its signature`() {
        val leaking = """
            @file:OptIn(org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi::class)

            package leak

            fun initiation(): org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation? = null

            fun take(initiations: List<org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation>): Int = initiations.size
        """.trimIndent()
        val caller = "fun one() = leak.initiation()\n\nfun two() = leak.take(listOf())"

        val errors = errors("Leak.kt" to leaking, "Use.kt" to caller)

        val lines = errors.map { Regex("Use\\.kt:(\\d+):").find(it)?.groupValues?.get(1) }.toSet()
        assertEquals(setOf("1", "3"), lines, errors.joinToString("\n"))
        assertTrue(errors.all { "Internal Alexandrite API" in it }, errors.joinToString("\n"))
    }

    @Test
    fun `the sample names every public declaration of the testkit`() {
        val missing = publicNames().filterNot { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(sample) }

        assertEquals(emptyList(), missing)
    }

    /** The names of the public classes, functions and properties in the testkit's root package. */
    private fun publicNames(): Set<String> {
        val root = PluginHarness::class.java.packageName
        val classes = Path.of(PluginHarness::class.java.protectionDomain.codeSource.location.toURI())
            .resolve(root.replace('.', '/'))
        val names = sortedSetOf<String>()
        Files.list(classes).use { files ->
            for (file in files.filter { it.extension == "class" }) {
                val type = Class.forName("$root.${file.nameWithoutExtension}")
                val nested = file.nameWithoutExtension.split('$').drop(1)
                if (generateSequence(type) { it.enclosingClass }.any { !Modifier.isPublic(it.modifiers) }) continue
                if (nested.any { !it.first().isUpperCase() || it == "DefaultImpls" }) continue
                if (!type.simpleName.endsWith("Kt") && type.simpleName != "Companion") names += type.simpleName
                for (method in type.declaredMethods) {
                    if (Modifier.isPublic(method.modifiers) && !method.isSynthetic && '$' !in method.name) {
                        names += declared(method)
                    }
                }
                for (field in type.declaredFields) {
                    if (Modifier.isPublic(field.modifiers) && field.name != "Companion") names += field.name
                }
            }
        }
        return names.filterNot { '$' in it || it in KOTLIN_MEMBERS || COMPONENT.matches(it) }.toSet()
    }

    /** The property that [method] reads or writes, or the function it is, without the mangling of value classes. */
    private fun declared(method: Method): String {
        val name = method.name.substringBefore('-')
        val accessor = when (method.parameterCount) {
            0 -> GETTER
            1 -> SETTER.takeIf { method.returnType == Void.TYPE }
            else -> null
        }
        val property = accessor?.matchEntire(name)?.groupValues?.get(1) ?: return name
        val acronym = property.length > 1 && property[1].isUpperCase()
        return if (acronym) property else property.replaceFirstChar(Char::lowercaseChar)
    }

    private companion object {
        val GETTER = Regex("(?:get|is)([A-Z].*)")
        val SETTER = Regex("set([A-Z].*)")
        val COMPONENT = Regex("component\\d+")
        val KOTLIN_MEMBERS = setOf("copy", "equals", "hashCode", "toString")
    }
}
