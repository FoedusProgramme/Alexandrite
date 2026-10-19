package org.unitedsoftware.alexandrite.agent.usage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.delivery.Notices
import org.unitedsoftware.alexandrite.agent.delivery.delivered
import org.unitedsoftware.alexandrite.agent.delivery.notice
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelPrice
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.turn.BudgetStatus
import org.unitedsoftware.alexandrite.sdk.turn.BudgetUnit
import org.unitedsoftware.alexandrite.sdk.turn.UsagePeriod
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** What the agents' model calls spend of their budgets, and the notices once a period's spending crosses a mark. */
@Singleton
internal class Budgets(
    records: Records,
    private val ledger: UsageLedger,
    private val directory: AgentDirectory,
    private val endpoints: Endpoints,
    private val topology: ChatTopology,
    private val channels: ChannelDirectory,
    private val notices: Notices,
    private val clock: Clock,
) : Lifecycle {
    private val noticed = records.collection<NoticedMark>(NOTICES)
    private val noticing = Mutex()
    private val refused = ConcurrentHashMap.newKeySet<String>()
    private val unpriced = ConcurrentHashMap.newKeySet<String>()

    override suspend fun onStart() {
        for (agent in directory.agents) checkPrices(agent)
        val oldest = LocalDate.now(clock).minusDays(2)
        for (record in noticed.list()) {
            if (LocalDate.parse(record.value.ends) < oldest) noticed.remove(record.key)
        }
    }

    fun zone(agent: AgentId): ZoneId = directory.agent(agent)?.zone ?: clock.zone

    fun today(agent: AgentId): LocalDate = clock.instant().atZone(zone(agent)).toLocalDate()

    /** Records [usage] of [model] in a turn of [agent] at [chat], and tells the agent once it crosses a mark. */
    suspend fun record(agent: AgentId, chat: ChatAddress, model: ModelRef, usage: Usage) {
        val price = price(model)
        val cost = price?.let { it.cost(usage) ?: BigDecimal.ZERO }
        if (cost == null && directory.agent(agent)?.hasMoneyBudget() == true && unpriced.add("$agent $model")) {
            logger.warn(
                "Agent '{}' has a budget in money, but model {} has no price: its calls cost nothing",
                agent,
                model,
            )
        }
        ledger.add(today(agent), agent, chat, model, usage, cost)
        crossed(agent, chat)
    }

    /** The budgets of [agent], each with what it spent in its current period. */
    suspend fun status(agent: AgentId): List<BudgetStatus> {
        val budget = directory.agent(agent)?.config?.budget ?: return emptyList()
        val today = today(agent)
        val periods = listOf(
            Triple(UsagePeriod.TODAY, budget.daily, today),
            Triple(UsagePeriod.THIS_MONTH, budget.monthly, today.withDayOfMonth(1)),
        )
        return periods.flatMap { (period, limit, from) ->
            if (limit == null) return@flatMap emptyList()
            val days = ledger.days(agent, from, today)
            listOfNotNull(
                limit.tokens?.let { tokens ->
                    val spent = days.sumOf { it.input + it.output }
                    BudgetStatus(period, BudgetUnit.TOKENS, BigDecimal.valueOf(tokens), BigDecimal.valueOf(spent))
                },
                limit.money?.let { money ->
                    val spent = days.fold(BigDecimal.ZERO) { sum, day -> sum + day.money }
                    BudgetStatus(period, BudgetUnit.MONEY, BigDecimal.valueOf(money), spent)
                },
            )
        }
    }

    /** Whether [agent] spent all of one of its budgets. */
    suspend fun exhausted(agent: AgentId): Boolean = status(agent).any { it.exhausted }

    /** Whether this is the first turn of [agent] at [chat] refused today. */
    fun firstRefusal(agent: AgentId, chat: ChatAddress): Boolean = refused.add("${today(agent)} $agent $chat")

    /** The price of [model], null when its endpoint lists none or cannot list its models. */
    private suspend fun price(model: ModelRef): ModelPrice? =
        endpoints.modelsWithin(model.endpoint, "to read the price of $model")?.firstOrNull {
            it.id == model.model
        }?.price

    /** Tells [agent] of each period whose spending crossed 80 % or 100 % since it was last told. */
    private suspend fun crossed(agent: AgentId, chat: ChatAddress) {
        for ((period, budgets) in status(agent).groupBy { it.period }) {
            val worst = budgets.maxBy { it.spent.divide(it.limit, FRACTION_SCALE, RoundingMode.DOWN) }
            val fraction = worst.spent.divide(worst.limit, FRACTION_SCALE, RoundingMode.DOWN)
            val mark = MARKS.lastOrNull { fraction >= it.toBigDecimal().movePointLeft(2) } ?: continue
            val today = today(agent)
            val ends = if (period == UsagePeriod.TODAY) today else today.withDayOfMonth(today.lengthOfMonth())
            val key = "$agent $period $ends"
            val first = noticing.withLock {
                val before = try {
                    noticed.get(key)?.value?.percent ?: 0
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    logger.warn(
                        "Cannot read whether agent '{}' was told of its {} budget: {}",
                        agent,
                        period,
                        e.toString(),
                    )
                    0
                }
                if (before >= mark) return@withLock false
                try {
                    noticed.put(key, NoticedMark(mark, ends.toString()), null)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    logger.warn(
                        "Cannot record that agent '{}' was told of its {} budget: {}",
                        agent,
                        period,
                        e.toString(),
                    )
                }
                true
            }
            if (first) tell(agent, chat, worst, mark, fraction.movePointRight(2).toInt())
        }
    }

    /** Sends the notice that [agent] spent [percent] % of [status], past [mark] %, to its home chat, else to [chat]. */
    private suspend fun tell(agent: AgentId, chat: ChatAddress, status: BudgetStatus, mark: Int, percent: Int) {
        logger.info("Agent '{}' spent {}% of its {} budget in {}", agent, percent, status.period, status.unit)
        val configured = directory.agent(agent)
        val target = configured?.attachments?.firstNotNullOfOrNull { topology.home(agent, it.instance) } ?: chat
        val key = AgentChatKey(agent, target)
        val text = notices.text(
            key,
            if (mark >= FULL) TextKeys.BUDGET_SPENT else TextKeys.BUDGET_WARNING,
            "agent" to (configured?.name ?: agent.value),
            "percent" to percent,
            "period" to notices.text(key, TextKeys.budgetPeriod(status.period)),
            "spent" to amount(key, status.unit, status.spent),
            "limit" to amount(key, status.unit, status.limit),
        )
        val delivery = channels.channel(target.instance)?.let { channel ->
            delivered { channel.send(target, notice(text, null, null)) }
        }
        if (delivery !is Delivery.Delivered) {
            logger.warn(
                "Chat {} is not told that agent '{}' spent {}% of a budget: {}",
                target,
                agent,
                percent,
                delivery,
            )
        }
    }

    private suspend fun amount(key: AgentChatKey, unit: BudgetUnit, value: BigDecimal): String =
        if (unit == BudgetUnit.MONEY) {
            notices.text(key, TextKeys.AMOUNT_MONEY, "amount" to money(value))
        } else {
            notices.text(key, TextKeys.AMOUNT_TOKENS, "count" to value.toPlainString())
        }

    /** Throws when [agent] has a budget in money and one of its models is listed without a price. */
    private suspend fun checkPrices(agent: Agent) {
        if (!agent.hasMoneyBudget()) return
        val models = listOfNotNull(agent.model, agent.judgeModel, agent.summaryModel).distinct()
        for (model in models) {
            val listed = endpoints.modelsWithin(model.endpoint, "to check the prices of agent '${agent.id}'")
            val info = listed?.firstOrNull { it.id == model.model }
            if (info == null) {
                logger.warn("Agent '{}' has a budget in money, but its model {} cannot be priced now", agent.id, model)
                continue
            }
            if (info.price == null) {
                throw ConfigException(
                    "agent.agents.${agent.id}.budget",
                    "a budget in money needs a price for every model, and $model has none: set " +
                        "models.\"${model.model}\".price in the config of endpoint '${model.endpoint}', or budget tokens",
                )
            }
        }
    }

    private companion object {
        const val NOTICES = "usage.notices"
        const val FULL = 100
        const val FRACTION_SCALE = 6
        val MARKS = listOf(80, FULL)
    }
}

/** The highest mark, in percent, that a budget's notices reached in the period that [ends] on that date. */
@Serializable
internal class NoticedMark(val percent: Int, val ends: String)

/** [value] in dollars, to the cent unless it is less than one. */
internal fun money(value: BigDecimal): String {
    val scale = if (value.signum() != 0 && value.abs() < BigDecimal.ONE) 4 else 2
    return value.setScale(scale, RoundingMode.HALF_UP).toPlainString()
}

private fun Agent.hasMoneyBudget(): Boolean = config.budget.daily?.money != null || config.budget.monthly?.money != null

private val logger = LoggerFactory.getLogger(Budgets::class.java)
