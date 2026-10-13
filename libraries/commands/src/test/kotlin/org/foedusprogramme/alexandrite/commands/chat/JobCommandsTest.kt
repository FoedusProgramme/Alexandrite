package org.foedusprogramme.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.commands.ADMIN
import org.foedusprogramme.alexandrite.commands.CHINESE
import org.foedusprogramme.alexandrite.commands.MEMBER
import org.foedusprogramme.alexandrite.commands.PLUGIN
import org.foedusprogramme.alexandrite.commands.TestClock
import org.foedusprogramme.alexandrite.commands.answer
import org.foedusprogramme.alexandrite.commands.commandContext
import org.foedusprogramme.alexandrite.commands.commandTexts
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.turn.JobStatus
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl.Call
import org.foedusprogramme.alexandrite.testkit.TEST_TIME
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class JobCommandsTest {
    private val control = RecordingAgentControl()
    private val clock = TestClock(TEST_TIME.plusSeconds(3725))
    private val commands = JobCommands(control, commandTexts(), clock)
    private val chat = testChat()
    private val build = JobStatus(RunId("j-7kq2mx"), chat, "command.exec", "./gradlew build", TEST_TIME, ADMIN)
    private val wait = JobStatus(RunId("j-0a1b2c"), chat, "notes.wait", "", TEST_TIME.plusSeconds(3600), null)

    private suspend fun jobs(arguments: String = "", issuer: ChatUser = ADMIN): String =
        commands.answer(testCommand("jobs", arguments, issuer = issuer))

    @Test
    fun `jobs lists the jobs of the chat with how long they run and who started them`() = runTest {
        val empty = jobs()
        val elsewhere = JobStatus(RunId("j-x"), testChat("other"), "fs.read", "", TEST_TIME, null)
        control.jobStatuses = listOf(build, wait, elsewhere)

        assertEquals("No job runs in this chat.", empty)
        assertEquals(
            "Jobs running in this chat (stop one with /jobs stop <id>):\n" +
                "j-7kq2mx: command.exec ./gradlew build, running for 1 h 3 min, started by Admin\n" +
                "j-0a1b2c: notes.wait, running for 3 min",
            jobs(issuer = MEMBER),
        )
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `jobs stop stops a job of the chat on behalf of the issuer, and says when there is none`() = runTest {
        control.jobStatuses = listOf(build)

        val replies = listOf(jobs("stop j-7kq2mx"), jobs("STOP j-nope"))

        assertEquals(
            listOf("Job j-7kq2mx is stopped.", "No job j-nope runs in this chat. Send /jobs to see the jobs."),
            replies,
        )
        assertEquals(
            listOf(Call.StopJob(chat, RunId("j-7kq2mx"), ADMIN), Call.StopJob(chat, RunId("j-nope"), ADMIN)),
            control.calls,
        )
    }

    @Test
    fun `anything but a bare jobs or one stop with an id changes nothing`() = runTest {
        val usage = "Send /jobs to see the jobs running in this chat, or /jobs stop <id> to stop one."

        val replies = listOf("stop", "j-7kq2mx", "stop j-1 j-2", "kill j-1").map { jobs(it) }

        assertEquals(List(4) { usage }, replies)
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `jobs answers in the language of the chat`() = runTest {
        val catalog = TextCatalog.builder().text(PLUGIN, CHINESE, "jobs.none", "没有后台任务。").build()
        val chinese = JobCommands(control, commandTexts(catalog), clock)
        val invocation = testCommand("jobs", issuer = ADMIN)

        assertEquals("没有后台任务。", chinese.answer(invocation, commandContext(invocation, language = CHINESE)))
    }
}
