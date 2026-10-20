package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.BOB
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.GROUP
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.TestRecords
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelegramReplayTest {
    private val telegram = TelegramHarness(outcome = null)
    private val fake = telegram.fake
    private val records = TestRecords()

    @AfterTest
    fun close() {
        telegram.close()
    }

    /** Runs the bot, keeping its records from one run to the next, with [more] in its config. */
    private fun run(more: String = "", block: suspend () -> Any?) {
        telegram.run(telegram.builder("work" to telegram.instance(more = more)).records(records)) { block() }
    }

    private val texts: List<String>
        get() = telegram.submitter.submissions.map { assertIs<Submission.Message>(it).message.text.orEmpty() }

    @Test
    fun `a message a stop cut off before it showed anything is answered after the restart, and only once`() {
        run {
            fake.send(telegram.bob, telegram.bobChat, "one")
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot run the migration")
            fake.send(telegram.ada, telegram.adaChat, "and this one ends")
            telegram.submitted(3)
            telegram.submitter.tickets[0].complete(TurnOutcome.ShutDown(replayable = true))
            telegram.submitter.tickets[1].complete(TurnOutcome.ShutDown(replayable = true))
            telegram.submitter.tickets[2].complete(TurnOutcome.Completed(null))
        }
        val polls = fake.offsets.size

        run {
            telegram.submitted(5)
            telegram.submitter.tickets.drop(3).forEach { it.complete(TurnOutcome.Completed(null)) }
        }
        run { telegram.consumed(3) }

        assertEquals(
            listOf("one", "run the migration", "and this one ends").let { it + it.take(2) },
            texts,
        )
        assertEquals(4L, fake.offsets[polls])
        assertEquals(emptyList(), fake.botMessages())
    }

    @Test
    fun `a turn a stop cut off once it showed something is not replayed, and its chat is told at once`() {
        val again = "The bot stopped before it finished answering this message. Send it again if you still need " +
            "the answer."
        run {
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot run the migration")
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot and the backfill")
            fake.send(telegram.bob, telegram.bobChat, "one")
            fake.send(telegram.bob, telegram.bobChat, "two")
            telegram.submitted(4)
            telegram.submitter.tickets.forEach { it.complete(TurnOutcome.ShutDown(replayable = false)) }
            fake.await { botMessages().takeIf { it.size == 3 } }
        }
        run { telegram.consumed(4) }

        assertEquals(4, texts.size)
        val group = fake.botMessages(GROUP).map { it.text to it.replyTo }
        assertEquals(listOf(again to 1L, again to 2L), group.sortedBy { it.second })
        assertEquals(listOf(again to null), fake.botMessages(BOB).map { it.text to it.replyTo })
    }

    @Test
    fun `a message whose turn the last run left unended is not replayed, and its chat is told after the restart`() {
        run {
            fake.send(telegram.bob, telegram.bobChat, "one")
            telegram.submitted(1)
        }
        assertEquals(emptyList(), fake.botMessages())

        run { fake.await { botMessages(BOB).singleOrNull() } }

        assertEquals(listOf("one"), texts)
        assertEquals(emptyList(), fake.botMessages(ADA))
    }

    @Test
    fun `a cut-off message older than the window is not answered after the restart`() {
        val window = """, "replayWindowMinutes": 60"""
        run(window) {
            fake.queue(
                buildJsonObject {
                    put("from", telegram.bob.json())
                    put("chat", telegram.bobChat.json())
                    put("date", Instant.now().minusSeconds(2 * 3600).epochSecond)
                    put("text", "old")
                },
            )
            fake.send(telegram.bob, telegram.bobChat, "new")
            telegram.submitted(2)
            telegram.submitter.tickets.forEach { it.complete(TurnOutcome.ShutDown(replayable = true)) }
        }

        run(window) { telegram.submitted(3) }
        run(window) { telegram.consumed(2) }

        assertEquals(listOf("old", "new", "new"), texts)
    }
}
