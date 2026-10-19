package org.unitedsoftware.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.CHINESE
import org.unitedsoftware.alexandrite.commands.GUEST
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.PLUGIN
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandContext
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.AgentUsage
import org.unitedsoftware.alexandrite.sdk.turn.BudgetStatus
import org.unitedsoftware.alexandrite.sdk.turn.BudgetUnit
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.ModelUsage
import org.unitedsoftware.alexandrite.sdk.turn.PeriodUsage
import org.unitedsoftware.alexandrite.sdk.turn.UsagePeriod
import org.unitedsoftware.alexandrite.sdk.turn.UsageReport
import org.unitedsoftware.alexandrite.testkit.RecordingAgentControl
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import java.math.BigDecimal
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageCommandsTest {
    private val control = RecordingAgentControl()
    private val commands = UsageCommands(control, commandTexts())
    private val chat = testChat()
    private val priced = ModelRef.parse("router/fast")
    private val unpriced = ModelRef.parse("local/big")
    private val helper = AgentId("helper")

    private fun periods(vararg models: List<ModelUsage>): List<PeriodUsage> =
        UsagePeriod.entries.zip(models).map { (period, used) -> PeriodUsage(period, used) }

    private fun report(vararg agents: AgentUsage) = UsageReport(chat, agents.toList())

    private val main = AgentUsage(
        AgentId.MAIN,
        ZoneOffset.UTC,
        periods(
            emptyList(),
            listOf(ModelUsage(priced, 1200, 200, 300, 50, 4, BigDecimal("0.0123"))),
            listOf(ModelUsage(priced, 1200, 200, 300, 50, 4, BigDecimal("0.0123"))),
        ),
        periods(
            listOf(ModelUsage(unpriced, 500, 0, 100, 0, 1, null)),
            listOf(
                ModelUsage(priced, 3000, 500, 700, 100, 9, BigDecimal("2.5")),
                ModelUsage(unpriced, 500, 0, 100, 0, 1, null),
            ),
            listOf(ModelUsage(priced, 9000, 1000, 1000, 300, 20, BigDecimal("7.25"))),
        ),
        listOf(
            BudgetStatus(UsagePeriod.TODAY, BudgetUnit.TOKENS, BigDecimal(100_000), BigDecimal(600)),
            BudgetStatus(UsagePeriod.THIS_MONTH, BudgetUnit.MONEY, BigDecimal("30.0"), BigDecimal("7.25")),
        ),
    )

    @Test
    fun `usage shows the chat's and the agent's tokens per period and model, money where priced, and budgets`() =
        runTest {
            control.scriptAgent(AgentId.MAIN, name = "Coder")
            control.scriptUsage(chat, report(main))

            assertEquals(
                """
                Usage of Coder in this chat:
                Today: no model calls.
                Last 7 days: 1500 tokens, $0.0123
                - router/fast: 1200 in (200 cached), 300 out (50 reasoning), 4 requests, $0.0123
                This month: 1500 tokens, $0.0123
                - router/fast: 1200 in (200 cached), 300 out (50 reasoning), 4 requests, $0.0123
                Usage of Coder in all its chats:
                Today: 600 tokens
                - local/big: 500 in (0 cached), 100 out (0 reasoning), 1 requests
                Last 7 days: 4300 tokens
                - router/fast: 3000 in (500 cached), 700 out (100 reasoning), 9 requests, $2.50
                - local/big: 500 in (0 cached), 100 out (0 reasoning), 1 requests
                This month: 10000 tokens, $7.25
                - router/fast: 9000 in (1000 cached), 1000 out (300 reasoning), 20 requests, $7.25
                Daily budget: 600 tokens of 100000 tokens (0%)
                Monthly budget: $7.25 of $30.00 (24%)
                """.trimIndent(),
                commands.answer(testCommand("usage", issuer = GUEST)),
            )
        }

    @Test
    fun `usage all asks for every agent and shows each other agent's own usage`() = runTest {
        val asked = mutableListOf<Pair<ChatAddress, Boolean>>()
        val other =
            AgentUsage(helper, ZoneOffset.UTC, emptyList(), periods(emptyList(), emptyList(), emptyList()), emptyList())
        val recording = object : AgentControl by control {
            override suspend fun usage(chat: ChatAddress, everyAgent: Boolean): UsageReport {
                asked += chat to everyAgent
                return report(main, other)
            }
        }
        control.scriptAgent(helper, name = "Helper")

        val reply = UsageCommands(recording, commandTexts()).answer(testCommand("usage", " ALL ", issuer = OPERATOR))

        assertEquals(listOf(chat to true), asked)
        assertEquals(
            """
            Usage of Helper in all its chats:
            Today: no model calls.
            Last 7 days: no model calls.
            This month: no model calls.
            """.trimIndent(),
            reply.lines().takeLast(4).joinToString("\n"),
        )
    }

    @Test
    fun `operators alone may ask for every agent, and anything else after the command shows how to use it`() = runTest {
        assertEquals(CommandForms.WITH_ARGUMENTS, commands.commands.single().operatorsOnly)
        assertEquals(
            "Send /usage to see the agent's usage, or /usage all to see that of every agent too.",
            commands.answer(testCommand("usage", "yesterday", issuer = OPERATOR)),
        )
    }

    @Test
    fun `usage answers in the language of the chat`() = runTest {
        val catalog = TextCatalog.builder().text(PLUGIN, CHINESE, "used.none", "{period}：没有模型调用。")
            .text(PLUGIN, CHINESE, "used.today", "今天")
            .build()
        val chinese = UsageCommands(control, commandTexts(catalog))
        val invocation = testCommand("usage", issuer = GUEST)

        val reply = chinese.answer(invocation, commandContext(invocation, language = CHINESE))

        assertEquals("今天：没有模型调用。", reply.lines()[1])
    }
}
