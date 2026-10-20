package org.unitedsoftware.alexandrite.agent.usage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.assertStartFails
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.delegation.BUDGET_SPENT_RESULT
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.job.JobRecords
import org.unitedsoftware.alexandrite.agent.startingInBackground
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.ModelPrice
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.BudgetStatus
import org.unitedsoftware.alexandrite.sdk.turn.BudgetUnit
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.ModelUsage
import org.unitedsoftware.alexandrite.sdk.turn.PeriodUsage
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.UsagePeriod
import org.unitedsoftware.alexandrite.sdk.turn.UsageReports
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingChannel.Event
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BudgetsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val coder = AgentId("coder")
    private val chat = ChatAddress.parse("test:main:chat")
    private val home = ChatAddress.parse("test:main:home")
    private val today = LocalDate.now(ZoneOffset.UTC)

    private fun config(budget: String = """{"daily": {"tokens": 1000}}""") = """
        {
          "previewIntervalMillis": 0,
          "agents": {
            "coder": {
              "name": "Coder",
              "model": "scripted/test-model",
              "zone": "UTC",
              "channels": {"test:main": {"default": true, "home": "home"}},
              "budget": $budget
            },
            "helper": {"description": "Helps.", "model": "scripted/test-model"}
          }
        }
    """.trimIndent()

    private fun harness(
        config: String = config(),
        model: ScriptedModel = this.model,
        configure: PluginHarness.Builder.() -> Unit = {},
    ): PluginHarness = agentHarness(config, store) {
        channel(operators = setOf("operator")).model(model).tool(recordingTool("fs.read")).apply(configure)
    }

    private fun usage(input: Long, output: Long, cached: Long = 0, reasoning: Long = 0): Usage = Usage.builder()
        .inputTokens(input)
        .cacheReadTokens(cached)
        .outputTokens(output)
        .reasoningTokens(reasoning)
        .build()

    /** Records [usage] for [date] as earlier runs would have. */
    private fun seed(usage: Usage, date: LocalDate = today, at: ChatAddress = chat, cost: BigDecimal? = null) {
        val ledger = UsageLedger(TestRecords(store).of("alexandrite-agent"), Clock.systemUTC())
        blocking { ledger.add(date, coder, at, model.ref, usage, cost) }
    }

    private suspend fun PluginHarness.Running.ask(text: String, sender: String = "operator"): TurnOutcome {
        val admission = channel().receive(text, chat, channel().user(sender))
        return assertIs<Admission.Accepted>(admission).ticket.outcome()
    }

    private suspend fun PluginHarness.Running.remind(): TurnOutcome {
        val turn = InitiatedTurn.builder(AgentChatKey(coder, chat), TurnKind.REMINDER, "Stretch.").build()
        return assertIs<Admission.Accepted>(get<TurnInitiation>().initiate("beat", turn)).ticket.outcome()
    }

    private fun PluginHarness.Running.notices(at: ChatAddress): List<String> =
        channel().sent.filter { it.chat == at && it.message.kind == MessageKind.NOTICE }.map { it.message.text }

    // Recording.

    @Test
    fun `each call's usage is recorded per chat and model and survives a restart`() {
        model.reply { text("One.").usage(usage(300, 40, cached = 100, reasoning = 10)) }
            .reply { text("Two.").usage(usage(200, 20)) }

        harness().execute { ask("First.") }
        harness().execute {
            ask("Second.")
            val report = get<UsageReports>().report(chat)

            val usage = report.agents.single()
            val expected = listOf(ModelUsage(model.ref, 500, 100, 60, 10, 2, null))
            assertEquals(coder to ZoneId.of("UTC"), usage.agent to usage.zone)
            assertEquals(List(3) { expected }, usage.chat.map { it.models })
            assertEquals(List(3) { expected }, usage.total.map { it.models })
            val spent = BudgetStatus(UsagePeriod.TODAY, BudgetUnit.TOKENS, BigDecimal(1000), BigDecimal(560))
            assertEquals(listOf(spent), usage.budgets)
        }
    }

    @Test
    fun `usage sums by period in the agent's zone, by chat and in all, with its cost where every call had a price`() {
        val priced = ScriptedModel(models = listOf(ScriptedModel.modelInfo().rebuild { price(PRICE) }))
        val other = ChatAddress.parse("test:main:other")
        seed(usage(1000, 100), today.minusDays(3), cost = BigDecimal("0.0045"))
        seed(usage(4000, 400), today.minusDays(20), cost = BigDecimal("0.018"))
        seed(usage(7, 3), today.minusDays(40))
        seed(usage(50, 5), today, other, BigDecimal("0.000225"))
        priced.reply { text("Done.").usage(usage(100, 10)) }

        harness(model = priced).execute {
            ask("Count this.")
            val usage = get<UsageReports>().report(chat, everyAgent = true).agents.single()

            fun tokens(period: UsagePeriod, chat: Boolean) =
                (if (chat) usage.chat else usage.total).single { it.period == period }.models.sumOf { it.tokens }
            val inMonth = today.minusDays(20).month == today.month
            val weekInMonth = today.minusDays(3).month == today.month
            assertEquals(110L, tokens(UsagePeriod.TODAY, chat = true))
            assertEquals(165L, tokens(UsagePeriod.TODAY, chat = false))
            assertEquals(1210L, tokens(UsagePeriod.LAST_7_DAYS, chat = true))
            assertEquals(1265L, tokens(UsagePeriod.LAST_7_DAYS, chat = false))
            val month = 165L + (if (weekInMonth) 1100 else 0) + (if (inMonth) 4400 else 0)
            assertEquals(month, tokens(UsagePeriod.THIS_MONTH, chat = false))
            fun cost(periods: List<PeriodUsage>) =
                periods.single { it.period == UsagePeriod.TODAY }.models.single().cost
            assertEquals(BigDecimal("0.00045") to BigDecimal("0.000675"), cost(usage.chat) to cost(usage.total))
        }
    }

    @Test
    fun `a call of a model without a price leaves the period's money unknown`() {
        seed(usage(1000, 100), cost = BigDecimal("0.01"))
        seed(usage(10, 1))

        harness().execute {
            val today = get<UsageReports>().report(chat).agents.single().chat.first().models.single()

            assertEquals(1111L to null, today.tokens to today.cost)
        }
    }

    // Notices.

    @Test
    fun `the home chat is told once at 80 and once at 100 percent of a period, a restart included`() {
        model.reply { text("A.").usage(usage(800, 50)) }
            .reply { text("B.").usage(usage(90, 10)) }
            .reply { text("C.").usage(usage(90, 10)) }
            .reply { text("D.").usage(usage(90, 10)) }

        val warned = "Coder has used 85% of its daily budget: 850 tokens of 1000 tokens."
        val spent = "Coder has used up its daily budget: 1050 tokens of 1000 tokens. Until the budget renews, it " +
            "answers only the messages of operators; scheduled tasks, heartbeats, background work and the messages " +
            "of others are refused."

        harness().execute {
            ask("One.")
            ask("Two.")
            assertEquals(listOf(warned), notices(home))
            ask("Three.")
            assertEquals(listOf(warned, spent), notices(home))
        }
        harness().execute {
            val before = notices(home)
            ask("Four.")

            assertEquals(before, notices(home))
            assertEquals(4, model.requests.size)
        }
    }

    // Past the budget.

    @Test
    fun `past the budget a guest's message and initiated turns are refused while an operator is still answered`() {
        seed(usage(2000, 100))
        model.reply { text("Still here.") }

        harness().execute {
            val guest = ask("Hello?", sender = "guest")
            val again = ask("Anyone?", sender = "guest")
            val reminded = remind()
            val remindedAgain = remind()

            assertEquals(listOf(TurnOutcome.Completed(null)), listOf(guest, again, reminded, remindedAgain).distinct())
            assertEquals(emptyList(), model.requests)
            val refused = "Coder has used up its budget, so this was not answered. It still answers the messages of " +
                "operators."
            assertEquals(List(3) { refused }, notices(chat))
            assertEquals(
                listOf("in-1", "in-2", null),
                channel().sent.map { it.message.replyTo?.id },
            )

            val answered = assertIs<TurnOutcome.Completed>(ask("Status?"))
            assertEquals("Still here.", answered.reply?.parts?.filterIsInstance<TextPart>()?.single()?.text)
            assertEquals(1, model.requests.size)
        }
    }

    @Test
    fun `past the budget an operator's turn starts no background run`() {
        seed(usage(2000, 100))
        model.reply { toolCall("agent.delegate", """{"profile":"helper","task":"Look.","background":true}""") }
            .reply { text("I will do it myself.") }

        harness().execute {
            ask("Delegate it.")

            val result = store.transcripts.entries(store.conversations.current(AgentChatKey(coder, chat)).id)
                .filterIsInstance<ToolResultEntry>()
                .single()
            assertEquals(ToolOutcome.NotRun(NotRunReason.BUDGET), result.outcome)
            assertEquals(BUDGET_SPENT_RESULT, (result.content.single() as TextPart).text)
        }
    }

    @Test
    fun `a job's result refused past the budget is dropped, and the next turn is told it came, after a restart too`() {
        val finish = CompletableDeferred<Unit>()
        val slow = recordingTool("test.slow") { _, _ ->
            finish.await()
            ToolResult("Slow done.")
        }
        model.reply { toolCall("test.slow", "{}").usage(usage(1500, 100)) }
            .reply { text("It runs in the background.") }
            .reply { text("It ended without a result I can see.") }
        var job: RunId? = null

        harness { tool(startingInBackground(slow)) }.execute {
            ask("Start it.")
            val jobs = JobRecords(TestRecords(store).of("alexandrite-agent"))
            val started = jobs.all().keys.single().also { job = it }
            finish.complete(Unit)
            val dropped =
                "Coder has used up its budget, so the result of job $started (test.slow) is not taken in. Its " +
                    "next turn here is told that the job ended."
            channel().awaitEvent { (it as? Event.Sent)?.message?.text == dropped }

            withTimeout(5_000) { while (jobs.all().isNotEmpty()) delay(5) }
            assertEquals(2, model.requests.size)
        }
        harness { tool(startingInBackground(slow)) }.execute {
            assertIs<TurnOutcome.Completed>(ask("What came of it?"))

            val history = model.requests.last().history.filterIsInstance<UserEntry>().takeLast(2)
            val framing = (history[0].parts.first() as ContextPart).text
            assertEquals(
                "[alexandrite:job]\nA background job ended: job $job, test.slow. Its result came after your budget " +
                    "was used up, so it is not shown: run the call again if you still need it.\n[/alexandrite:job]",
                framing,
            )
            assertTrue("Slow done." !in model.requests.last().history.toString())
            assertEquals(UserOrigin.FromChat::class, history[1].origin::class)
        }
    }

    // Money.

    @Test
    fun `a budget in money fails the start when a model of the agent has no price`() {
        harness(config("""{"monthly": {"money": 5}}""")).assertStartFails(
            StartStage.START,
            "Invalid config at 'agent.agents.coder.budget': a budget in money needs a price for every model, and " +
                "scripted/test-model has none: set models.\"test-model\".price in the config of endpoint 'scripted', " +
                "or budget tokens",
        )
    }

    @Test
    fun `a budget in money counts the cost of priced calls`() {
        val priced = ScriptedModel(models = listOf(ScriptedModel.modelInfo().rebuild { price(PRICE) }))
        priced.reply { text("Done.").usage(usage(1_000_000, 100_000)) }

        harness(config("""{"monthly": {"money": 5}}"""), priced).execute {
            ask("Spend.")

            val status = get<UsageReports>().report(chat).agents.single().budgets.single()
            assertEquals(BigDecimal("4.5"), status.spent)
            assertEquals(
                listOf("Coder has used 90% of its monthly budget: $4.50 of $5.00."),
                notices(home),
            )
        }
    }

    private companion object {
        /** Three dollars per million input tokens, fifteen per million output tokens. */
        val PRICE = ModelPrice(BigDecimal(3), BigDecimal(15))
    }
}
