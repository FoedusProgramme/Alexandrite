package org.unitedsoftware.alexandrite.agent.usage

import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.testkit.TestRecords
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageLedgerTest {
    private val clock = TestClock(Instant.parse("2026-10-19T12:00:00Z"))
    private val records = TestRecords()
    private val coder = AgentId("coder")
    private val chat = ChatAddress.parse("test:main:chat")
    private val model = ModelRef.parse("scripted/test-model")
    private val today = LocalDate.parse("2026-10-19")

    private fun ledger() = UsageLedger(records.of("alexandrite-agent"), clock)

    private fun usage(input: Long, output: Long, cached: Long = 0, reasoning: Long = 0) = Usage.builder()
        .inputTokens(input)
        .cacheReadTokens(cached)
        .outputTokens(output)
        .reasoningTokens(reasoning)
        .build()

    @Test
    fun `a day sums its calls per chat and model, and a restart reads it back`() {
        val other = ChatAddress.parse("test:main:other")
        blocking {
            val ledger = ledger()
            ledger.add(today, coder, chat, model, usage(1000, 200, cached = 400, reasoning = 50), BigDecimal("0.01"))
            ledger.add(today, coder, chat, model, usage(500, 100), BigDecimal("0.005"))
            ledger.add(today, coder, other, model, usage(10, 1), null)
        }

        val days = blocking { ledger().days(coder, today, today) }.associateBy { it.chat }

        val here = days.getValue(chat)
        assertEquals(
            listOf(1500L, 400L, 300L, 50L, 2L, 0L),
            listOf(here.input, here.cached, here.output, here.reasoning, here.requests, here.unpriced),
        )
        assertEquals(BigDecimal("0.015"), here.money)
        assertEquals(1L to BigDecimal.ZERO, days.getValue(other).unpriced to days.getValue(other).money)
    }

    @Test
    fun `days are read by agent and range, and days long past are dropped at the start`() {
        val helper = AgentId("helper")
        blocking {
            val ledger = ledger()
            for (date in listOf(today, today.minusDays(6), today.minusDays(7), today.minusDays(500))) {
                ledger.add(date, coder, chat, model, usage(100, 10), null)
            }
            ledger.add(today, helper, chat, model, usage(1, 1), null)
        }
        val ledger = ledger()
        blocking { ledger.onStart() }

        assertEquals(
            listOf(today.minusDays(6), today),
            blocking { ledger.days(coder, today.minusDays(6), today) }.map { it.day }.sorted(),
        )
        assertEquals(setOf(coder, helper), blocking { ledger.agents(today.minusDays(1)) })
        assertEquals(
            4,
            blocking {
                records.of("alexandrite-agent").collection("usage", DayUsage.serializer()).list()
            }.size,
        )
    }
}
