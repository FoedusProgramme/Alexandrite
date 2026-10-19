package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.automation.common.When
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.turn.Mandate
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testDelegatedTurn
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The tools: every time form and its refusals, who may schedule, mandates, scopes and limits. */
class ScheduleToolsTest {
    private fun test(block: suspend TestScope.() -> Unit) = runTest { block() }

    private suspend fun TestScope.opened(config: ScheduleConfig = ScheduleConfig()) =
        Schedule(this, config = config).open()

    private suspend fun Schedule.created(arguments: Map<String, String>): String {
        val result = call(create, mapOf("prompt" to "Check the build.") + arguments, mandate = MANDATE)
        assertEquals(false, result.isError, result.text)
        return result.text
    }

    private suspend fun Schedule.refused(arguments: Map<String, String>, prompt: String = "Check the build."): String {
        val before = control.list(KEY)
        val result = call(create, mapOf("prompt" to prompt) + arguments, mandate = MANDATE)
        assertEquals(true, result.isError, result.text)
        assertEquals(before, control.list(KEY))
        return result.text
    }

    // Time forms.

    @Test
    fun `at is a local time in the chat's zone, and the answer tells the next run there`() = test {
        val schedule = opened()

        val answer = schedule.created(mapOf("at" to "2026-10-19T09:00"))

        assertEquals("Scheduled ${ID.find(answer)!!.value}: next run 2026-10-19 09:00 +08:00.", answer)
        val task = schedule.control.list(KEY).single()
        assertEquals(When.Once(LocalDateTime.parse("2026-10-19T09:00")), task.schedule)
        assertEquals(SHANGHAI to Instant.parse("2026-10-19T01:00:00Z"), task.zone to task.next)
        assertEquals(Triple(CHAT, OPERATOR.address, "Ada"), Triple(task.chat, task.createdBy, task.creatorName))
    }

    @Test
    fun `at takes a space and seconds, and refuses a time that is past or no time`() = test {
        val schedule = opened()

        assertContains(schedule.created(mapOf("at" to "2026-10-18 18:30:15")), "next run 2026-10-18 18:30 +08:00")
        schedule.call(schedule.cancel, mapOf("id" to schedule.control.list(KEY).single().id))
        assertContains(schedule.refused(mapOf("at" to "2026-10-18T17:59")), "not due after now")
        assertContains(schedule.refused(mapOf("at" to "tomorrow at nine")), "no local date-time")
    }

    @Test
    fun `in is a delay in minutes and up`() = test {
        val schedule = opened()

        assertContains(schedule.created(mapOf("in" to "1h30m")), "next run 2026-10-18 19:30 +08:00.")
        assertEquals(ORIGIN.plusSeconds(90 * 60), schedule.control.list(KEY).single().next)
        assertContains(schedule.created(mapOf("in" to "2d")), "next run 2026-10-20 18:00 +08:00.")
        for (refused in listOf("90s", "1h30s", "0m", "soon", "-5m")) {
            val result = schedule.call(schedule.create, mapOf("prompt" to "x", "in" to refused), mandate = MANDATE)
            assertEquals(true, result.isError, refused)
        }
        assertContains(
            schedule.call(schedule.create, mapOf("prompt" to "x", "in" to "90s")).text,
            "minutes and up, such as 30m",
        )
    }

    @Test
    fun `every repeats from now, at least as far apart as the minimum`() = test {
        val schedule = opened()

        val answer = schedule.created(mapOf("every" to "2h"))

        assertContains(answer, "next run 2026-10-18 20:00 +08:00, then every 2h.")
        assertEquals(When.Every(2.hours, ORIGIN), schedule.control.list(KEY).single().schedule)
        assertContains(schedule.refused(mapOf("every" to "4m")), "at most once every 5m, not every 4m")
    }

    @Test
    fun `cron fires in the chat's zone, and one firing closer than the minimum is refused`() = test {
        val schedule = opened()

        val answer = schedule.created(mapOf("cron" to "0 9 * * MON-FRI"))

        assertContains(answer, "next run 2026-10-19 09:00 +08:00, then by cron 0 9 * * MON-FRI.")
        schedule.call(schedule.cancel, mapOf("id" to ID.find(answer)!!.value))
        assertContains(schedule.refused(mapOf("cron" to "*/2 * * * *")), "fires 2m apart at times")
        assertContains(schedule.refused(mapOf("cron" to "0,3 9 * * *")), "fires 3m apart at times")
        assertContains(schedule.refused(mapOf("cron" to "0 25 * * *")), "Bad cron")
        assertContains(schedule.created(mapOf("cron" to "@daily")), "next run 2026-10-19 00:00 +08:00")
    }

    @Test
    fun `exactly one time form, and a prompt`() = test {
        val schedule = opened()

        assertContains(schedule.refused(emptyMap()), "exactly one of at, in, every or cron")
        assertContains(schedule.refused(mapOf("in" to "1h", "every" to "1h")), "exactly one of")
        assertContains(schedule.refused(mapOf("in" to "1h"), prompt = " "), "Give the prompt")
        assertContains(schedule.refused(mapOf("in" to "1h"), prompt = "x".repeat(4001)), "more than 4000 characters")
    }

    // Who may schedule.

    @Test
    fun `a guest's turn, an automated turn and a sub-agent may neither schedule nor cancel`() = test {
        val schedule = opened()
        val id = schedule.make(mapOf("in" to "1h"))
        val automated = { act: String -> "own message can $act tasks; a scheduled or other automated turn cannot" }
        val turns = mapOf(
            turn(GUEST) to { act: String -> "Only an operator can $act tasks." },
            turn(OPERATOR, kind = TurnKind.REMINDER) to automated,
            turn(null, kind = TurnKind.HEARTBEAT) to automated,
            testDelegatedTurn(turn()) to { act: String -> "A sub-agent cannot $act tasks." },
        )

        for ((turn, reason) in turns) {
            val made = schedule.call(schedule.create, mapOf("prompt" to "x", "in" to "1h"), turn, MANDATE)
            val cancelled = schedule.call(schedule.cancel, mapOf("id" to id), turn)
            assertEquals(true to true, made.isError to cancelled.isError)
            assertContains(made.text, reason("schedule"))
            assertContains(cancelled.text, reason("cancel"))
        }
        assertEquals(listOf(id), schedule.control.list(KEY).map { it.id })
    }

    @Test
    fun `a guest may list the chat's tasks`() = test {
        val schedule = opened()
        val id = schedule.make(mapOf("every" to "1d"))
        schedule.make(mapOf("cron" to "30 8 * * *"))

        val listed = schedule.call(schedule.list, emptyMap(), turn(GUEST))

        assertEquals(
            "The tasks scheduled in this chat:\n" +
                "- $id: every 1d, next 2026-10-19 18:00 +08:00, set by Ada: Check the build.\n" +
                "- ${schedule.control.list(KEY)[1].id}: cron 30 8 * * * in Asia/Shanghai, next 2026-10-19 08:30 " +
                "+08:00, set by Ada: Check the build.",
            listed.text,
        )
    }

    @Test
    fun `listing a task whose prompt runs untrusted taints the turn, and listing trusted ones does not`() = test {
        val schedule = opened()
        schedule.make(mapOf("in" to "1h"))
        val trusted = testToolContext(turn())
        schedule.list.execute(JsonObject(emptyMap()), trusted)
        val id = schedule.make(mapOf("in" to "2h"), mandate = null)
        val untrusted = testToolContext(turn())
        val listed = schedule.list.execute(JsonObject(emptyMap()), untrusted)

        assertEquals(emptyList(), trusted.untrustedReasons)
        assertEquals(
            listOf("schedule.list shows the prompt of task $id, which runs untrusted"),
            untrusted.untrustedReasons,
        )
        assertContains(listed.text, "$id: once")
    }

    // Under taint.

    @Test
    fun `under taint a person sees the prompt and time of a new task and the id of a cancelled one`() = test {
        val schedule = opened()
        val tainted = testToolContext(turn()).apply { this.tainted = true }
        val arguments = JsonObject(mapOf("prompt" to JsonPrimitive("Post the feed."), "every" to JsonPrimitive("1d")))

        val guestContext = testToolContext(turn(GUEST)).apply { this.tainted = true }

        val created = (schedule.create as ReviewedTool).review(arguments, tainted)
        val cancelled =
            (schedule.cancel as ReviewedTool).review(JsonObject(mapOf("id" to JsonPrimitive("t-4K2P"))), tainted)
        val clean = (schedule.create as ReviewedTool).review(arguments, testToolContext(turn()))
        val guest = (schedule.create as ReviewedTool).review(arguments, guestContext)

        val why = "the conversation took in untrusted content, and a scheduled task outlasts it"
        val shown = ReviewPreview.Text("Schedule in this chat, every 1d:\n\nPost the feed.")
        assertEquals(why to shown, created.person to created.preview)
        val cancelling = ReviewPreview.Text("Cancel task t-4k2p of this chat.")
        assertEquals(why to cancelling, cancelled.person to cancelled.preview)
        assertEquals(CallReview.builder().build(), clean)
        assertEquals(CallReview.builder().build(), guest)
    }

    // Mandates.

    @Test
    fun `with a mandate the task runs as the operator, trusted, without one as nobody, untrusted`() = test {
        val schedule = opened()
        val trusted = schedule.make(mapOf("in" to "10m"), mandate = MANDATE)
        val answer = schedule.call(schedule.create, mapOf("prompt" to "Check the logs.", "in" to "20m")).text

        assertContains(
            answer,
            "Its turns run as nobody, untrusted, because this conversation took in untrusted content",
        )
        assertEquals(listOf(MANDATE, null), schedule.control.list(KEY).map { it.mandate })
        schedule.advance(20.minutes)
        val (first, second) = schedule.initiator.turns
        assertEquals(Triple(Trust.TRUSTED, MANDATE, TurnKind.REMINDER), Triple(first.trust, first.mandate, first.kind))
        assertEquals(Trust.UNTRUSTED to null, second.trust to second.mandate)
        assertContains(first.prompt, "Scheduled task $trusted, set by Ada")
    }

    @Test
    fun `a task that cannot be made revokes the mandate it was given`() = test {
        val schedule = opened()

        schedule.refused(mapOf("every" to "1m"))

        assertEquals(listOf(MANDATE), schedule.initiator.revoked)
    }

    // Scope and limits.

    @Test
    fun `list and cancel see only the tasks of their own chat`() = test {
        val schedule = opened()
        val ours = schedule.make(mapOf("in" to "1h"))
        val elsewhere = turn(chat = OTHER_CHAT)
        val theirs = schedule.make(mapOf("in" to "2h"), elsewhere, Mandate("m-other"))

        assertEquals(listOf(ours), schedule.control.list(KEY).map { it.id })
        assertContains(schedule.call(schedule.list, emptyMap(), elsewhere).text, theirs)
        val foreign = schedule.call(schedule.cancel, mapOf("id" to ours), elsewhere)
        assertEquals(true to "No task $ours is scheduled in this chat.", foreign.isError to foreign.text)
        assertEquals("Cancelled $ours.", schedule.call(schedule.cancel, mapOf("id" to ours.uppercase())).text)
        assertEquals(listOf(MANDATE), schedule.initiator.revoked)
        assertEquals(emptyList(), schedule.control.list(KEY))
        assertEquals("No task is scheduled in this chat.", schedule.call(schedule.list, emptyMap()).text)
    }

    @Test
    fun `a chat and an agent hold as many tasks as the config lets them`() = test {
        val schedule = opened(ScheduleConfig(maxPerChat = 2, maxPerAgent = 3))
        repeat(2) { schedule.make(mapOf("in" to "1h")) }

        assertContains(schedule.refused(mapOf("in" to "1h")), "This chat has 2 tasks, as many as it may")
        schedule.make(mapOf("in" to "1h"), turn(chat = OTHER_CHAT))
        val third = schedule.call(schedule.create, mapOf("prompt" to "x", "in" to "1h"), turn(chat = testChat("third")))
        assertContains(third.text, "Agent main has 3 tasks, as many as it may")
        val coder = turn(chat = testChat("third")) { agent(AgentId("coder")) }
        schedule.make(mapOf("in" to "1h"), coder)
        assertEquals(1, schedule.control.list(AgentChatKey(AgentId("coder"), testChat("third"))).size)
    }

    @Test
    fun `ids are short and unique`() = test {
        val schedule = opened()

        val ids = List(30) { schedule.make(mapOf("in" to "${it + 1}h"), turn(chat = testChat("c$it"))) }

        assertEquals(30, ids.toSet().size)
        assertTrue(ids.all { Regex("t-[0-9a-hjkmnp-tv-z]{4}").matches(it) }, "$ids")
    }
}
