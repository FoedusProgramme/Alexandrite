package org.unitedsoftware.alexandrite.agent.usage

import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.AgentUsage
import org.unitedsoftware.alexandrite.sdk.turn.ModelUsage
import org.unitedsoftware.alexandrite.sdk.turn.PeriodUsage
import org.unitedsoftware.alexandrite.sdk.turn.UsagePeriod
import org.unitedsoftware.alexandrite.sdk.turn.UsageReport
import java.math.BigDecimal
import java.time.LocalDate

/** The agents' recorded usage, summed by period and model. */
@Singleton
internal class UsageSums(
    private val ledger: UsageLedger,
    private val budgets: Budgets,
    private val directory: AgentDirectory,
) {
    /** The usage of [agent], at [chat] and in all, and of every other agent when [everyAgent]. */
    suspend fun report(agent: AgentId, chat: ChatAddress, everyAgent: Boolean): UsageReport {
        val others = if (everyAgent) {
            val since = budgets.today(agent).minusDays(DAYS_BEFORE + MAX_MONTH_DAYS)
            val served = directory.agents.filter { it.attachments.isNotEmpty() }.map { it.id }
            (served + ledger.agents(since)).distinct() - agent
        } else {
            emptyList()
        }
        return UsageReport(chat, listOf(of(agent, chat)) + others.map { of(it, null) })
    }

    private suspend fun of(agent: AgentId, chat: ChatAddress?): AgentUsage {
        val today = budgets.today(agent)
        val from = minOf(today.minusDays(DAYS_BEFORE), today.withDayOfMonth(1))
        val days = ledger.days(agent, from, today)
        val atChat = if (chat == null) emptyList() else periods(days.filter { it.chat == chat }, today)
        return AgentUsage(agent, budgets.zone(agent), atChat, periods(days, today), budgets.status(agent))
    }

    private fun periods(days: List<DayUsage>, today: LocalDate): List<PeriodUsage> = listOf(
        PeriodUsage(UsagePeriod.TODAY, models(days.filter { it.day == today })),
        PeriodUsage(UsagePeriod.LAST_7_DAYS, models(days.filter { it.day >= today.minusDays(DAYS_BEFORE) })),
        PeriodUsage(UsagePeriod.THIS_MONTH, models(days.filter { it.day >= today.withDayOfMonth(1) })),
    )

    private fun models(days: List<DayUsage>): List<ModelUsage> = days.groupBy { it.model }.map { (model, ofModel) ->
        ModelUsage(
            model,
            ofModel.sumOf { it.input },
            ofModel.sumOf { it.cached },
            ofModel.sumOf { it.output },
            ofModel.sumOf { it.reasoning },
            ofModel.sumOf { it.requests },
            ofModel.takeIf { all ->
                all.all { it.unpriced == 0L }
            }?.fold(BigDecimal.ZERO) { sum, day -> sum + day.money },
        )
    }.sortedByDescending { it.tokens }

    private companion object {
        /** The days before today in the last seven. */
        const val DAYS_BEFORE = 6L
        const val MAX_MONTH_DAYS = 31L
    }
}
