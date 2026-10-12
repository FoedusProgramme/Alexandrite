package org.foedusprogramme.alexandrite.agent.delegation

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.permission.Refusals
import org.foedusprogramme.alexandrite.agent.prompt.BASE_SECTION
import org.foedusprogramme.alexandrite.agent.prompt.DELEGATION_SECTION
import org.foedusprogramme.alexandrite.agent.prompt.WORKSPACE_SECTION
import org.foedusprogramme.alexandrite.agent.tool.CANCELLED_RESULT
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalAnswer
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalRequest
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.channel.TurnEventKind
import org.foedusprogramme.alexandrite.sdk.channel.rebuild
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.ToolChoice
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.permission.Clearance
import org.foedusprogramme.alexandrite.sdk.permission.ClearanceKind
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.store.ConversationKind
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.ForkPoint
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.RequestMatch
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DelegationTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val read = recordingTool(
        ToolDefinition(
            "test.read",
            "Reads a file.",
            JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ToolRisk.READ_ONLY,
            activity = ToolActivity.READ,
            titleArguments = listOf("path"),
        ),
    ) { _, _ -> ToolResult("The notes.") }
    private val cleared = CopyOnWriteArrayList<Clearance?>()
    private val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE) { _, context ->
        cleared += context.clearance
        ToolResult("Written.")
    }
    private val fetch = recordingTool("web.fetch", ToolRisk.NETWORK_READ) { _, context ->
        context.untrusted("a web page")
        ToolResult("Page.")
    }

    private fun harness(coder: String = ""): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}},
              "tools": {"forMembers": ["agent.delegate", "test.read", "fs.write"]}$coder
            },
            "reviewer": {"description": "Reviews.", "instructions": ["$REVIEWER"], "tools": {"allow": ["test.read"]}},
            "writer": {
              "description": "Writes.",
              "instructions": ["$WRITER"],
              "tools": {"allow": ["test.read", "fs.write"]}
            },
            "planner": {
              "description": "Plans.",
              "instructions": ["$PLANNER"],
              "tools": {"allow": ["fs.write"]},
              "permission": {"mode": "plan"}
            },
            "doer": {
              "description": "Does.",
              "instructions": ["$DOER"],
              "tools": {"allow": ["fs.write"]},
              "permission": {"mode": "auto"}
            },
            "reader": {"description": "Reads the web.", "instructions": ["$READER"], "tools": {"allow": ["web.fetch"]}},
            "nester": {
              "description": "Delegates.",
              "instructions": ["$NESTER"],
              "tools": {"allow": ["agent.delegate", "test.read"]}
            }
          }
        }
        """.trimIndent(),
        store,
    ) { channel(admins = setOf("admin")).model(model).tool(read).tool(write).tool(fetch) }

    private fun PluginHarness.Running.ask(text: String = "Go.", user: String = "admin", chat: String = "chat") =
        channel().receive(text, channel().chat(chat), channel().user(user)) as Admission.Accepted

    private suspend fun PluginHarness.Running.mode(mode: PermissionMode?) {
        val update = ChatSettingsUpdate.builder().apply { if (mode == null) resetMode() else mode(mode) }.build()
        get<AgentControl>().updateSettings(channel().chat(), update, channel().user("admin"))
    }

    /** The tool results of the current conversation of [key]. */
    private suspend fun rootResults(key: AgentChatKey = this.key): List<ToolResultEntry> =
        store.transcripts.entries(store.conversations.current(key).id).filterIsInstance<ToolResultEntry>()

    private suspend fun results(conversation: ConversationId): List<ToolResultEntry> =
        store.transcripts.entries(conversation).filterIsInstance<ToolResultEntry>()

    /** The requests of the runs whose profile has [instructions]. */
    private fun runs(instructions: String): List<ModelRequest> = model.requests.filter { it.ofProfile(instructions) }

    /** The lineage of the run that made [request]. */
    private suspend fun lineage(request: ModelRequest): TurnLineage =
        checkNotNull(store.conversations.conversation(request.ids.conversation)?.lineage)

    private fun ToolResultEntry.text(): String = content.joinToString("") { (it as TextPart).text }

    private fun ToolResultEntry.shown(): Pair<ToolOutcome, String> = outcome to text()

    // What runs and what the turn above gets.

    @Test
    fun `a review delegated to a read-only profile returns its reply, and the profile's write tool is refused`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review notes.md.")) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "notes.md"}""") }
            .reply(profile(REVIEWER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
            .reply(profile(REVIEWER)) { text("Looks fine.") }
            .reply(ROOT) { text("The reviewer says it looks fine.") }

        harness().execute {
            val turn = ask("Have notes.md reviewed.")
            assertIs<TurnOutcome.Completed>(turn.ticket.outcome())

            val runs = runs(REVIEWER)
            assertEquals(List(3) { listOf("test.read") }, runs.map { request -> request.tools.map { it.name } })
            assertEquals(
                listOf(BASE_SECTION, DELEGATION_SECTION, "agent.instructions.1", WORKSPACE_SECTION),
                runs[0].instructions.map { it.id },
            )
            val opening = runs[0].history.single() as UserEntry
            assertEquals(UserOrigin.SystemWritten(DELEGATION_SOURCE), opening.origin)
            assertEquals("Review notes.md.", (opening.parts.last() as TextPart).text)
            val child = runs[0].ids.conversation
            assertEquals(
                listOf(
                    ToolOutcome.Succeeded to "The notes.",
                    ToolOutcome.NotRun(NotRunReason.NOT_PERMITTED) to "This turn may not call 'fs.write'.",
                ),
                results(child).map { it.shown() },
            )
            assertEquals(emptyList(), write.calls)
            assertEquals(listOf(ToolOutcome.Succeeded to "Looks fine."), rootResults().map { it.shown() })
            assertEquals("The reviewer says it looks fine.", channel().reply(turn.ticket.turn).completed?.text)

            val root = store.conversations.current(key).id
            val call = store.transcripts.entries(root).filterIsInstance<AssistantEntry>().first()
                .parts.filterIsInstance<ToolCallPart>().single().id
            val conversation = checkNotNull(store.conversations.conversation(child))
            assertEquals(
                listOf(ConversationKind.DELEGATED, ConversationState.SEALED, key, null),
                listOf(conversation.kind, conversation.state, conversation.key, conversation.fork),
            )
            val lineage = TurnLineage(lineage(runs[0]).run, turn.ticket.turn, root, call, turn.ticket.turn, root, 1)
            assertEquals(lineage, conversation.lineage)
            val record = checkNotNull(store.conversations.turn(runs[0].ids.turn))
            assertEquals(
                listOf(TurnKind.DELEGATED, TurnEndKind.COMPLETED, channel().chat(), channel().user("admin").address),
                listOf(record.kind, record.end, record.chat, record.actor),
            )
            assertTrue(lineage.run.value.startsWith("r-"), "${lineage.run}")
        }
    }

    @Test
    fun `an implementation delegated to a write-capable profile asks the root chat, naming the chain`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
            .reply(profile(WRITER)) { text("Notes written.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            val admin = channel().user("admin")
            channel().scriptApprovals(ApprovalAnswer.Allowed(admin))
            ask("Write the notes.").ticket.outcome()

            val request = channel().approvals.single()
            assertEquals(listOf(AgentId("coder"), AgentId("writer")), request.chain)
            assertEquals(
                listOf(channel().chat(), TurnKind.DELEGATED, ReplyTarget.CALLER, admin, "the chat is in ask mode"),
                listOf(
                    request.turn.chat,
                    request.turn.kind,
                    request.turn.replyTarget,
                    request.turn.actor,
                    request.reason,
                ),
            )
            assertEquals(listOf(Clearance(ClearanceKind.HUMAN, admin)), cleared.toList())
            assertEquals(listOf(ToolOutcome.Succeeded to "Notes written."), rootResults().map { it.shown() })
        }
    }

    @Test
    fun `a run that fails gives the turn above a failed result that names its notice`() {
        val broken = ModelError.builder(ModelErrorKind.INVALID_REQUEST, "Broken.").retryable(false).build()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review it.")) }
            .fail(broken, profile(REVIEWER))
            .reply(ROOT) { text("The review failed.") }

        harness().execute {
            ask().ticket.outcome()

            val result = rootResults().single()
            assertEquals(ToolOutcome.Failed, result.outcome)
            val child = checkNotNull(model.requests.firstOrNull { it.ofProfile(REVIEWER) }).ids.conversation
            val notice = store.transcripts.entries(child).filterIsInstance<NoticeEntry>().single()
            assertEquals(NoticeKind.FAILED, notice.kind)
            val run = lineage(model.requests.first { it.ofProfile(REVIEWER) }).run
            assertEquals(
                "Sub-agent run $run of reviewer failed: ${notice.text}. Its conversation $child keeps what it did.",
                result.text(),
            )
        }
    }

    @Test
    fun `an unknown profile, a background run and a bad context are refused as invalid arguments`() {
        model.reply(ROOT) {
            toolCall(DELEGATE_TOOL, delegate("nobody", "Do it."))
            toolCall(DELEGATE_TOOL, """{"profile": "reviewer", "task": "Do it.", "background": true}""")
            toolCall(DELEGATE_TOOL, """{"profile": "reviewer", "task": "Do it.", "context": "all"}""")
        }.reply(ROOT) { text("Nothing ran.") }

        harness().execute {
            ask().ticket.outcome()

            val invalid = ToolOutcome.NotRun(NotRunReason.INVALID_ARGUMENTS)
            assertEquals(
                setOf(
                    invalid to "No profile 'nobody'. Choose one of reviewer, writer, planner, doer, reader, nester.",
                    invalid to "Background delegation is not available yet: leave out 'background', and wait for " +
                        "the result.",
                    invalid to "'context' is \"fresh\" or \"fork\".",
                ),
                rootResults().map { it.shown() }.toSet(),
            )
        }
    }

    @Test
    fun `the tool's description lists the profiles that a turn may run`() {
        model.reply(ROOT) { text("Hello.") }

        harness().execute {
            ask().ticket.outcome()

            val description = model.requests.single().tools.single { it.name == DELEGATE_TOOL }.description
            assertContains(description, "\n\nProfiles:\n- reviewer (reviewer): Reviews.\n- writer (writer): Writes.\n")
        }
    }

    // Inheritance.

    @Test
    fun `a profile only ever tightens the mode, so plan under a bypass plans and auto under ask asks`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("planner", "Write a.")) }
            .reply(profile(PLANNER)) { toolCall("fs.write", """{"path": "a"}""") }
            .reply(profile(PLANNER)) { text("Planned.") }
            .reply(ROOT) { text("Planned.") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("doer", "Write b.")) }
            .reply(profile(DOER)) { toolCall("fs.write", """{"path": "b"}""") }
            .reply(profile(DOER)) { text("Asked.") }
            .reply(ROOT) { text("Asked.") }

        harness().execute {
            mode(PermissionMode.BYPASS)
            ask("Plan it.").ticket.outcome()
            mode(null)
            channel().scriptApprovals(ApprovalAnswer.Denied(channel().user("admin")))
            ask("Ask for it.").ticket.outcome()

            val planned = results(runs(PLANNER)[0].ids.conversation).single()
            assertEquals(ToolOutcome.NotRun(NotRunReason.PLAN_MODE), planned.outcome)
            val request = channel().approvals.single()
            assertEquals("the chat is in ask mode", request.reason)
            assertEquals(
                ToolOutcome.NotRun(NotRunReason.DENIED, request.id),
                results(runs(DOER)[0].ids.conversation).single().outcome,
            )
            assertEquals(emptyList(), write.calls)
            assertTrue(model.requests.none { it.isJudge() })
        }
    }

    @Test
    fun `a run that a member's turn delegates stays the member's, so its write asks an admin`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
            .reply(profile(WRITER)) { text("Not written.") }
            .reply(ROOT) { text("Not written.") }

        harness().execute {
            mode(PermissionMode.BYPASS)
            ask(user = "member").ticket.outcome()

            val request = channel().approvals.single()
            assertEquals(channel().user("member") to "a member's call", request.turn.actor to request.reason)
            assertEquals(listOf(AgentId("coder"), AgentId("writer")), request.chain)
            assertEquals(emptyList(), write.calls)
        }
    }

    @Test
    fun `in auto mode the judge sees what the operator typed and the delegated task, labelled`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write the plan into notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
            .reply(JUDGE) { text("""{"verdict": "allow", "reason": "the operator asked for it"}""") }
            .reply(profile(WRITER)) { text("Written.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            mode(PermissionMode.AUTO)
            ask("Please put the plan in notes.md.").ticket.outcome()

            val judged = model.requests.single { it.isJudge() }
            val prompt = (judged.history.single() as UserEntry).parts.joinToString("") { (it as TextPart).text }
            assertContains(prompt, "one block per message:\n<<<\nPlease put the plan in notes.md.\n>>>\n")
            assertContains(prompt, "not the operator:\ncoder → writer:\n<<<\nWrite the plan into notes.md.\n>>>\n")
            assertEquals(listOf(Clearance(ClearanceKind.JUDGE)), cleared.toList())
        }
    }

    @Test
    fun `a run of a tainted turn starts tainted, and a run that takes in untrusted content taints the turn above`() {
        model.reply(ROOT) { toolCall("web.fetch") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review it.")) }
            .reply(profile(REVIEWER)) { text("Fine.") }
            .reply(ROOT) { text("Fine.") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reader", "Read the page.")) }
            .reply(profile(READER)) { toolCall("web.fetch") }
            .reply(profile(READER)) { text("It says hello.") }
            .reply(ROOT) { text("It says hello.") }

        harness().execute {
            ask().ticket.outcome()
            ask(chat = "other").ticket.outcome()

            val tainted = store.conversations.current(key).id
            val down = runs(REVIEWER).single()
            assertEquals(
                "run ${lineage(down).run} was delegated by a turn of tainted conversation $tainted (a call of " +
                    "web.fetch, which reads the network)",
                store.conversations.taint(down.ids.conversation)?.reason,
            )
            val other = AgentChatKey.parse("coder@test:main:other")
            val up = runs(READER).first()
            assertEquals(
                "sub-agent run ${lineage(up).run} (reader) took in untrusted content: a call of web.fetch, which " +
                    "reads the network",
                store.conversations.taint(store.conversations.current(other).id)?.reason,
            )
        }
    }

    @Test
    fun `a forked run starts from the turn's history up to its call, a fresh one from its task alone`() {
        model.reply(ROOT) { text("Hello.") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review what we said.", "fork")) }
            .reply(profile(REVIEWER)) { text("Forked.") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review it fresh.")) }
            .reply(profile(REVIEWER)) { text("Fresh.") }
            .reply(ROOT) { text("Both done.") }

        harness().execute {
            ask("Hi.").ticket.outcome()
            ask("Review us.").ticket.outcome()

            val (forked, fresh) = runs(REVIEWER)
            val root = store.conversations.current(key).id
            val before = store.transcripts.entries(root).takeWhile { entry ->
                entry !is AssistantEntry || entry.parts.none { it is ToolCallPart }
            }
            assertEquals(3, before.size)
            assertEquals(before, forked.history.dropLast(1))
            assertEquals(listOf(UserEntry::class), fresh.history.map { it::class })
            assertEquals(
                listOf(ForkPoint(root, checkNotNull(before.last().record).id), null),
                listOf(forked, fresh).map { store.conversations.conversation(it.ids.conversation)?.fork },
            )
            assertEquals(
                listOf(ToolOutcome.Succeeded to "Forked.", ToolOutcome.Succeeded to "Fresh."),
                rootResults().map { it.shown() },
            )
        }
    }

    // Limits.

    @Test
    fun `runs delegate three levels deep at most, the last level is offered no delegation and its call is refused`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 1.")) }
            .reply(task("Level 1.")) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 2.")) }
            .reply(task("Level 2.")) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 3.")) }
            .reply(task("Level 3.")) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 4.")) }
            .reply(task("Level 3.")) { text("Three.") }
            .reply(task("Level 2.")) { text("Two.") }
            .reply(task("Level 1.")) { text("One.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            val turn = ask()
            turn.ticket.outcome()

            val levels = listOf("Level 1.", "Level 2.", "Level 3.").map { level ->
                model.requests.first { it.task() == level }
            }
            assertEquals(
                listOf(listOf(DELEGATE_TOOL, "test.read"), listOf(DELEGATE_TOOL, "test.read"), listOf("test.read")),
                levels.map { request -> request.tools.map { it.name } },
            )
            val lineages = levels.map { lineage(it) }
            assertEquals(listOf(1, 2, 3), lineages.map { it.depth })
            assertEquals(List(3) { turn.ticket.turn }, lineages.map { it.rootTurn })
            assertEquals(
                ToolOutcome.NotRun(NotRunReason.DELEGATION_LIMIT) to DELEGATION_LIMIT_RESULT,
                results(levels[2].ids.conversation).single().shown(),
            )
            assertEquals(
                listOf("Two.", "Three."),
                levels.take(2).map { results(it.ids.conversation).single().text() },
            )
        }
    }

    @Test
    fun `the root turn's token budget covers its runs, which sum up once it is used up`() {
        val used = Usage.builder().inputTokens(1100).outputTokens(100).build()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review a and b.")) }
            .reply(profile(REVIEWER)) {
                toolCall("test.read", """{"path": "a"}""")
                usage(used)
            }
            .reply(profile(REVIEWER)) { text("Read a; b is left.") }
            .reply(ROOT) { text("Half reviewed.") }

        harness(""", "maxTurnTokens": 1000""").execute {
            ask().ticket.outcome()

            assertEquals(listOf(ToolChoice.Auto, ToolChoice.None), runs(REVIEWER).map { it.toolChoice })
            assertEquals(ToolChoice.None, model.requests.last().toolChoice)
            val child = runs(REVIEWER)[0].ids.conversation
            assertEquals(
                listOf(NoticeKind.BUDGET),
                store.transcripts.entries(child).filterIsInstance<NoticeEntry>().map { it.kind },
            )
            assertEquals(listOf(ToolOutcome.Succeeded to "Read a; b is left."), rootResults().map { it.shown() })
        }
    }

    @Test
    fun `a run's long call stays in the foreground, so that its result comes back within the run`() {
        read.hold()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Read a.")) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "a"}""") }
            .reply(profile(REVIEWER)) { text("Read a.") }
            .reply(ROOT) { text("Read a.") }

        harness(""", "foregroundSeconds": 1""").execute {
            val turn = ask()
            read.awaitCalls(1)
            delay(1_500)
            assertEquals(emptyList(), get<AgentControl>().jobs(channel().chat()))
            read.release()
            turn.ticket.outcome()

            assertEquals(listOf(ToolOutcome.Succeeded), results(runs(REVIEWER)[0].ids.conversation).map { it.outcome })
            assertEquals(listOf(ToolOutcome.Succeeded to "Read a."), rootResults().map { it.shown() })
        }
    }

    // Approvals, cancel and denials.

    @Test
    fun `a cancel of the root turn stops its run and the approval that the run waits for`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }

        harness().execute {
            channel().holdApprovals()
            val turn = ask()
            val request = channel().awaitApprovals(1).single()

            assertTrue(get<AgentControl>().cancel(channel().chat(), channel().user("admin")))

            assertEquals(TurnOutcome.Cancelled, turn.ticket.outcome())
            channel().awaitEvent { it == RecordingChannel.Event.ApprovalWithdrawn(request.id) }
            val child = request.turn
            assertEquals(TurnEndKind.CANCELLED, store.conversations.turn(child.id)?.end)
            assertEquals(
                listOf(ToolOutcome.NotRun(NotRunReason.CANCELLED, request.id) to Refusals.CANCELLED),
                results(child.conversation).map { it.shown() },
            )
            assertEquals(listOf(ToolOutcome.Cancelled to CANCELLED_RESULT), rootResults().map { it.shown() })
            assertEquals(ConversationState.SEALED, store.conversations.conversation(child.conversation)?.state)
            assertTrue(get<AgentControl>().turns().none { it.turn.kind == TurnKind.DELEGATED })
            assertEquals(emptyList(), write.calls)
        }
    }

    @Test
    fun `a shutdown cuts the run off, its waiting call answered as interrupted and the delegate call as cancelled`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
        lateinit var turn: Admission.Accepted
        lateinit var request: ApprovalRequest

        harness().execute {
            channel().holdApprovals()
            turn = ask()
            request = channel().awaitApprovals(1).single()
            stop()
        }

        assertEquals(TurnOutcome.ShutDown(replayable = false), blocking { turn.ticket.outcome() })
        val run = request.turn
        assertEquals(
            listOf(ToolOutcome.NotRun(NotRunReason.INTERRUPTED, request.id) to Refusals.INTERRUPTED),
            blocking { results(run.conversation) }.map { it.shown() },
        )
        assertEquals(listOf(ToolOutcome.Cancelled to CANCELLED_RESULT), blocking { rootResults() }.map { it.shown() })
        assertEquals(TurnEndKind.SHUT_DOWN, blocking { store.conversations.turn(run.id) }?.end)
        assertEquals(ConversationState.SEALED, blocking { store.conversations.conversation(run.conversation) }?.state)
    }

    @Test
    fun `cancelling a run with its tree stops the runs below it, and the turn above goes on`() {
        read.hold()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 1.")) }
            .reply(task("Level 1.")) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 2.")) }
            .reply(task("Level 2.")) { toolCall("test.read", """{"path": "a"}""") }
            .reply(ROOT) { text("The run was stopped.") }

        harness().execute {
            val turn = ask()
            read.awaitCalls(1)
            val control = get<AgentControl>()
            val runs = control.turns().filter { it.turn.kind == TurnKind.DELEGATED }
            assertEquals(listOf(1, 2), runs.map { checkNotNull(it.turn.lineage).depth }.sorted())
            assertEquals(List(2) { TurnPhase.RUNNING to channel().chat() }, runs.map { it.phase to it.turn.chat })
            val first = runs.single { it.turn.lineage?.depth == 1 }.turn

            assertTrue(control.cancelRun(checkNotNull(first.lineage).run, tree = true, channel().user("admin")))

            assertIs<TurnOutcome.Completed>(turn.ticket.outcome())
            assertEquals(1, read.cancelled.size)
            val stopped = "Cancelled (outcome unknown): Sub-agent run ${first.lineage?.run} of nester was stopped " +
                "before it ended."
            assertEquals(listOf(ToolOutcome.Cancelled to stopped), rootResults().map { it.shown() })
            assertEquals(TurnEndKind.CANCELLED, store.conversations.turn(first.id)?.end)
            assertEquals("The run was stopped.", channel().reply(turn.ticket.turn).completed?.text)
        }
    }

    @Test
    fun `an admin's denial in a run refuses the same call again anywhere in the tree without asking`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write a.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "a"}""") }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "a"}""") }
            .reply(profile(WRITER)) { text("Not written.") }
            .reply(ROOT) { toolCall("fs.write", """{"path": "a"}""") }
            .reply(ROOT) { text("Not written either.") }

        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Denied(channel().user("admin")))
            ask().ticket.outcome()

            val request = channel().approvals.single()
            val denied = ToolOutcome.NotRun(NotRunReason.DENIED)
            assertEquals(
                listOf(
                    ToolOutcome.NotRun(NotRunReason.DENIED, request.id) to Refusals.DENIED_BY_ADMIN,
                    denied to Refusals.DENIED_IN_TREE,
                ),
                results(request.turn.conversation).map { it.shown() },
            )
            assertEquals(
                listOf(ToolOutcome.Succeeded to "Not written.", denied to Refusals.DENIED_IN_TREE),
                rootResults().map { it.shown() },
            )
            assertEquals(emptyList(), write.calls)
        }
    }

    // Events.

    @Test
    fun `the root turn's channel is shown the run's tool calls, tagged with the run and its profile`() {
        val delegateCall = ToolCallId("delegate")
        val readCall = ToolCallId("read")
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review notes.md."), delegateCall) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "notes.md"}""", readCall) }
            .reply(profile(REVIEWER)) { text("Fine.") }
            .reply(ROOT) { text("Fine.") }

        harness().execute {
            val capabilities = RecordingChannel.DEFAULT_CAPABILITIES.rebuild { events(TurnEventKind.entries.toSet()) }
            channel().scriptCapabilities(capabilities)
            val turn = ask()
            turn.ticket.outcome()

            val run = lineage(runs(REVIEWER)[0]).run
            assertEquals(
                listOf(
                    TurnEvent.ToolCallStarted(delegateCall, DELEGATE_TOOL, ToolActivity.OTHER, "reviewer"),
                    TurnEvent.ToolCallUpdated(delegateCall, ToolCallStatus.RUNNING),
                    TurnEvent.ToolCallStarted(readCall, "test.read", ToolActivity.READ, "notes.md", run, REVIEWER_ID),
                    TurnEvent.ToolCallUpdated(readCall, ToolCallStatus.RUNNING, null, run),
                    TurnEvent.ToolCallUpdated(readCall, ToolCallStatus.COMPLETED, null, run),
                    TurnEvent.ToolCallUpdated(delegateCall, ToolCallStatus.COMPLETED),
                ),
                channel().reply(turn.ticket.turn).activity.filter {
                    it is TurnEvent.ToolCallStarted || it is TurnEvent.ToolCallUpdated
                },
            )
            assertEquals(2, channel().reply(turn.ticket.turn).activity.count { it is TurnEvent.UsageUpdated })
        }
    }

    private companion object {
        const val REVIEWER = "Review strictly."
        const val WRITER = "Write the change."
        const val PLANNER = "Only plan."
        const val DOER = "Act at once."
        const val READER = "Read the web."
        const val NESTER = "Hand work down."
        val REVIEWER_ID = AgentId("reviewer")

        /** The requests of a top-level turn, which neither a run nor the judge makes. */
        val ROOT = RequestMatch("the root turn") { request ->
            request.instructions.none { it.id == DELEGATION_SECTION } && !request.isJudge()
        }

        val JUDGE = RequestMatch("the judge") { it.isJudge() }

        /** The requests of a run of the profile whose instructions are [instructions]. */
        fun profile(instructions: String) = RequestMatch("a run of \"$instructions\"") { it.ofProfile(instructions) }

        /** The requests of a run given [task]. */
        fun task(task: String) = RequestMatch("a run given \"$task\"") { it.task() == task }

        fun delegate(profile: String, task: String, context: String? = null): String = JsonObject(
            listOfNotNull(
                "profile" to JsonPrimitive(profile),
                "task" to JsonPrimitive(task),
                context?.let { "context" to JsonPrimitive(it) },
            ).toMap(),
        ).toString()

        fun ModelRequest.ofProfile(instructions: String): Boolean = this.instructions.any { it.text == instructions }

        fun ModelRequest.isJudge(): Boolean = instructions.any { it.id == "permission-judge" }

        /** The task that the run of this request was given, null for a request of no run. */
        fun ModelRequest.task(): String? = history.filterIsInstance<UserEntry>()
            .firstOrNull { it.origin == UserOrigin.SystemWritten(DELEGATION_SOURCE) }
            ?.parts?.filterIsInstance<TextPart>()?.lastOrNull()?.text
    }
}
