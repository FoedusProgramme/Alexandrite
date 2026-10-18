package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.testkit.TestCommandContext
import org.unitedsoftware.alexandrite.testkit.assertTextsComplete
import org.unitedsoftware.alexandrite.testkit.testCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleCommandTest {
    private fun test(block: suspend TestScope.() -> Unit) = runTest { block() }

    /** What `/schedule [arguments]` from [issuer] replies. */
    private suspend fun Schedule.run(arguments: String, issuer: ChatUser = MEMBER): String {
        val context = TestCommandContext(turn(issuer, kind = TurnKind.COMMAND))
        command.handle(testCommand("schedule", arguments, CHAT, issuer), context)
        return context.replies.single().text
    }

    @Test
    fun `anyone lists the chat's tasks, with times in the chat's zone`() = test {
        val schedule = Schedule(this).open()
        assertEquals("No task is scheduled in this chat.", schedule.run(""))
        val once = schedule.make(mapOf("at" to "2026-10-19T09:00"))
        val daily = schedule.make(mapOf("cron" to "0 8 * * *"))
        val hourly = schedule.make(mapOf("every" to "1h30m"), mandate = null)
        schedule.make(mapOf("in" to "1h"), turn(chat = OTHER_CHAT))

        assertEquals(
            "Tasks scheduled in this chat:\n" +
                "$once: once, next 2026-10-19 09:00 +08:00, set by Ada: Check the build.\n" +
                "$daily: cron 0 8 * * * (Asia/Shanghai), next 2026-10-19 08:00 +08:00, set by Ada: Check the build.\n" +
                "$hourly: every 1h30m, next 2026-10-18 19:30 +08:00, set by Ada: Check the build.",
            schedule.run(""),
        )
    }

    @Test
    fun `only an admin cancels, and only a task of this chat`() = test {
        val schedule = Schedule(this).open()
        val id = schedule.make(mapOf("in" to "1h"))
        val theirs = schedule.make(mapOf("in" to "1h"), turn(chat = OTHER_CHAT))

        assertEquals("Only an administrator can cancel a scheduled task.", schedule.run("cancel $id"))
        assertEquals("No task $theirs is scheduled in this chat.", schedule.run("cancel $theirs", ADMIN))
        assertEquals("Cancelled task $id.", schedule.run("CANCEL ${id.uppercase()}", ADMIN))
        assertEquals(listOf(MANDATE), schedule.initiator.revoked)
        assertEquals("No task is scheduled in this chat.", schedule.run(""))
    }

    @Test
    fun `other arguments get the usage`() = test {
        val schedule = Schedule(this).open()

        for (arguments in listOf("cancel", "list all", "cancel a b")) {
            assertEquals(
                "Send /schedule to see the tasks scheduled in this chat, or /schedule cancel <id> to cancel one.",
                schedule.run(arguments, ADMIN),
            )
        }
    }

    @Test
    fun `the command is described, and the texts are complete`() = test {
        val schedule = Schedule(this)

        assertEquals(listOf("schedule"), schedule.command.commands.map { it.name })
        assertEquals(
            "Lists the tasks scheduled in this chat, or cancels one.",
            schedule.command.commands[0].description,
        )
        assertTextsComplete(PLUGIN)
    }
}
