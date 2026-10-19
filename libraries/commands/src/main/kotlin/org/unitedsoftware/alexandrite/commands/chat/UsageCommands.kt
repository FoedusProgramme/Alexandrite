package org.unitedsoftware.alexandrite.commands.chat

import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.isKeyword
import org.unitedsoftware.alexandrite.commands.support.refusing
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.commands.support.words
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.BudgetStatus
import org.unitedsoftware.alexandrite.sdk.turn.BudgetUnit
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.ModelUsage
import org.unitedsoftware.alexandrite.sdk.turn.PeriodUsage
import org.unitedsoftware.alexandrite.sdk.turn.rebuild
import java.math.BigDecimal
import java.math.RoundingMode

/** `/usage`, and `/usage all` for operators. */
@Singleton
@Contribute(CommandHandler::class)
internal class UsageCommands(private val control: AgentControl, private val texts: Texts) : CommandHandler {
    override val commands: List<CommandSpec> =
        listOf(texts.spec(USAGE).rebuild { operatorsOnly(CommandForms.WITH_ARGUMENTS) })

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn)
        val words = words(invocation.arguments)
        val all = words.singleOrNull()?.isKeyword(ALL) == true
        if (words.isNotEmpty() && !all) {
            context.reply(replies.usage(USAGE))
            return
        }
        val report = context.refusing { control.usage(invocation.chat, all) } ?: return
        val names = control.agents(invocation.chat.instance).associate { it.id to it.name }
        fun name(agent: AgentId) = names[agent] ?: agent.value
        val lines = mutableListOf<String>()
        for ((index, usage) in report.agents.withIndex()) {
            if (index == 0) {
                lines += replies.text(TextKeys.USED_CHAT, "agent" to name(usage.agent))
                lines += usage.chat.flatMap { lines(it, replies) }
            }
            lines += replies.text(TextKeys.USED_AGENT, "agent" to name(usage.agent))
            lines += usage.total.flatMap { lines(it, replies) }
            lines += usage.budgets.map { budget(it, replies) }
        }
        context.reply(lines.joinToString("\n"))
    }

    private fun lines(usage: PeriodUsage, replies: Replies): List<String> {
        val period = replies.text(TextKeys.usedPeriod(usage.period))
        if (usage.models.isEmpty()) return listOf(replies.text(TextKeys.USED_NONE, "period" to period))
        val tokens = usage.models.sumOf { it.tokens }
        val costs = usage.models.map { it.cost }
        val total = if (costs.any { it == null }) {
            replies.text(TextKeys.USED_TOTAL, "period" to period, "tokens" to tokens)
        } else {
            val cost = money(costs.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add))
            replies.text(TextKeys.USED_TOTAL_COST, "period" to period, "tokens" to tokens, "cost" to cost)
        }
        return listOf(total) + usage.models.map { model(it, replies) }
    }

    private fun model(usage: ModelUsage, replies: Replies): String {
        val counts = arrayOf(
            "model" to usage.model,
            "input" to usage.inputTokens,
            "cached" to usage.cachedTokens,
            "output" to usage.outputTokens,
            "reasoning" to usage.reasoningTokens,
            "requests" to usage.requests,
        )
        val cost = usage.cost ?: return replies.text(TextKeys.USED_MODEL, *counts)
        return replies.text(TextKeys.USED_MODEL_COST, *counts, "cost" to money(cost))
    }

    private fun budget(status: BudgetStatus, replies: Replies): String {
        val percent = status.spent.multiply(HUNDRED).divide(status.limit, 0, RoundingMode.DOWN)
        return replies.text(
            TextKeys.USED_BUDGET,
            "period" to replies.text(TextKeys.usedBudgetPeriod(status.period)),
            "spent" to amount(status.unit, status.spent, replies),
            "limit" to amount(status.unit, status.limit, replies),
            "percent" to percent.toPlainString(),
        )
    }

    private fun amount(unit: BudgetUnit, value: BigDecimal, replies: Replies): String = if (unit == BudgetUnit.MONEY) {
        replies.text(TextKeys.USED_MONEY, "amount" to money(value))
    } else {
        replies.text(TextKeys.USED_TOKENS, "count" to value.toPlainString())
    }

    /** [value] in dollars, to the cent unless it is less than one. */
    private fun money(value: BigDecimal): String {
        val scale = if (value.signum() != 0 && value.abs() < BigDecimal.ONE) 4 else 2
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    private companion object {
        const val USAGE = "usage"
        const val ALL = "all"
        val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
    }
}
