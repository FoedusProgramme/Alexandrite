package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.math.BigDecimal
import java.time.ZoneId

/** The model usage of the agents. */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface UsageReports {
    /** The usage of the agent that serves [chat], there and in all, and of every other agent when [everyAgent]. */
    public suspend fun report(chat: ChatAddress, everyAgent: Boolean = false): UsageReport
}

/** The model usage shown at [chat]: that of the chat's agent first, then of the other agents asked for. */
@Poko
public class UsageReport private constructor(public val chat: ChatAddress, public val agents: List<AgentUsage>) {
    public companion object {
        public operator fun invoke(chat: ChatAddress, agents: List<AgentUsage>): UsageReport =
            UsageReport(chat, agents.toList())
    }
}

/** What [agent]'s model calls used, summed by period in its [zone]. */
@Poko
public class AgentUsage private constructor(
    public val agent: AgentId,
    public val zone: ZoneId,
    /** What the agent used at the chat of the report, empty for the other agents. */
    public val chat: List<PeriodUsage>,
    public val total: List<PeriodUsage>,
    public val budgets: List<BudgetStatus>,
) {
    public companion object {
        public operator fun invoke(
            agent: AgentId,
            zone: ZoneId,
            chat: List<PeriodUsage>,
            total: List<PeriodUsage>,
            budgets: List<BudgetStatus>,
        ): AgentUsage = AgentUsage(agent, zone, chat.toList(), total.toList(), budgets.toList())
    }
}

@Poko
public class PeriodUsage private constructor(
    public val period: UsagePeriod,
    /** The most tokens first. */
    public val models: List<ModelUsage>,
) {
    public companion object {
        public operator fun invoke(period: UsagePeriod, models: List<ModelUsage>): PeriodUsage =
            PeriodUsage(period, models.toList())
    }
}

/** The tokens of one model's calls in a period. */
@Poko
public class ModelUsage(
    public val model: ModelRef,
    /** Prompt tokens, cached ones included. */
    public val inputTokens: Long,
    public val cachedTokens: Long,
    /** Tokens the model wrote, reasoning included. */
    public val outputTokens: Long,
    public val reasoningTokens: Long,
    public val requests: Long,
    /** In US dollars, null unless every call had a price. */
    public val cost: BigDecimal?,
) {
    public val tokens: Long get() = inputTokens + outputTokens
}

/** A period that usage is summed over, in its agent's zone. */
@JvmInline
@Serializable
public value class UsagePeriod internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val TODAY: UsagePeriod = UsagePeriod("today")

        /** Today and the six days before. */
        public val LAST_7_DAYS: UsagePeriod = UsagePeriod("last_7_days")

        /** From the first of this month. */
        public val THIS_MONTH: UsagePeriod = UsagePeriod("this_month")

        /** The values this version knows. */
        public val entries: List<UsagePeriod> = listOf(TODAY, LAST_7_DAYS, THIS_MONTH)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): UsagePeriod = UsagePeriod(id)
    }
}

/** A budget of an agent and what it spent of it in the current [period]. */
@Poko
public class BudgetStatus(
    /** [UsagePeriod.TODAY] or [UsagePeriod.THIS_MONTH]. */
    public val period: UsagePeriod,
    public val unit: BudgetUnit,
    public val limit: BigDecimal,
    public val spent: BigDecimal,
) {
    /** Whether the agent spent all of it. */
    public val exhausted: Boolean get() = spent >= limit
}

/** What a budget counts. */
@JvmInline
@Serializable
public value class BudgetUnit internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** Input and output tokens together. */
        public val TOKENS: BudgetUnit = BudgetUnit("tokens")

        /** US dollars. */
        public val MONEY: BudgetUnit = BudgetUnit("money")

        /** The values this version knows. */
        public val entries: List<BudgetUnit> = listOf(TOKENS, MONEY)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): BudgetUnit = BudgetUnit(id)
    }
}
