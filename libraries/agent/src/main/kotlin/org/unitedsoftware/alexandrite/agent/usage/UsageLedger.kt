package org.unitedsoftware.alexandrite.agent.usage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/** The usage of the agents' model calls, summed per day, agent, chat and model in records that survive restarts. */
@Singleton
internal class UsageLedger(records: Records, private val clock: Clock) : Lifecycle {
    private val stored = records.collection<DayUsage>(COLLECTION)
    private val lock = Mutex()
    private var days: MutableMap<String, DayUsage>? = null

    override suspend fun onStart() {
        lock.withLock { loaded() }
    }

    /** Adds [usage] of [model] at [chat] to [agent]'s [date], where [cost] is null for a call that has no price. */
    suspend fun add(
        date: LocalDate,
        agent: AgentId,
        chat: ChatAddress,
        model: ModelRef,
        usage: Usage,
        cost: BigDecimal?,
    ) {
        lock.withLock {
            val days = loaded()
            val key = "$date $agent $chat $model"
            val day = (days[key] ?: DayUsage(date.toString(), agent, chat, model)).plus(usage, cost)
            days[key] = day
            try {
                stored.put(key, day, null)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn("The usage of {} on {} is not stored, so a restart loses it: {}", agent, date, e.toString())
            }
        }
    }

    /** The days of [agent] from [from] through [to]. */
    suspend fun days(agent: AgentId, from: LocalDate, to: LocalDate): List<DayUsage> =
        lock.withLock { loaded().values.filter { it.agent == agent && it.day in from..to } }

    /** The agents that used a model since [from]. */
    suspend fun agents(from: LocalDate): Set<AgentId> = lock.withLock {
        loaded().values.filter { it.day >= from }.mapTo(sortedSetOf(compareBy { it.value })) { it.agent }
    }

    /** The days recorded, those older than [KEPT_DAYS] removed. */
    private suspend fun loaded(): MutableMap<String, DayUsage> {
        days?.let { return it }
        val oldest = LocalDate.now(clock).minusDays(KEPT_DAYS)
        val loaded = HashMap<String, DayUsage>()
        for (record in stored.list()) {
            if (record.value.day < oldest) stored.remove(record.key) else loaded[record.key] = record.value
        }
        days = loaded
        return loaded
    }

    private companion object {
        const val COLLECTION = "usage"
        const val KEPT_DAYS = 400L
    }
}

/** What the model calls of [agent] at [chat] took of [model] on [date]. */
@Serializable
internal class DayUsage(
    /** An ISO-8601 date in the agent's zone. */
    val date: String,
    val agent: AgentId,
    val chat: ChatAddress,
    val model: ModelRef,
    val input: Long = 0,
    val cached: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val requests: Long = 0,
    /** US dollars of the calls that had a price, as a decimal. */
    val cost: String = "0",
    /** The calls that had no price. */
    val unpriced: Long = 0,
) {
    val day: LocalDate get() = LocalDate.parse(date)

    val money: BigDecimal get() = BigDecimal(cost)

    fun plus(usage: Usage, cost: BigDecimal?): DayUsage = DayUsage(
        date,
        agent,
        chat,
        model,
        input + (usage.inputTokens ?: 0),
        cached + (usage.cacheReadTokens ?: 0),
        output + (usage.outputTokens ?: 0),
        reasoning + (usage.reasoningTokens ?: 0),
        requests + usage.requests,
        (cost?.let(money::plus) ?: money).toPlainString(),
        unpriced + if (cost == null) 1 else 0,
    )
}

private val logger = LoggerFactory.getLogger(UsageLedger::class.java)
