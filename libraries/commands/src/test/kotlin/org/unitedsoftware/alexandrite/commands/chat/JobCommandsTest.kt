package org.unitedsoftware.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.GUEST
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.TestClock
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.turn.JobStatus
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingAgent.Call
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class JobCommandsTest {
    private val control = RecordingAgent()
    private val clock = TestClock(TEST_TIME.plusSeconds(3725))
    private val commands = JobCommands(control.turns, commandTexts(), clock)
    private val chat = testChat()
    private val build =
        JobStatus(RunId("j-7kq2mx"), chat, AgentId.MAIN, "command.exec", "./gradlew build", TEST_TIME, OPERATOR)
    private val wait =
        JobStatus(RunId("j-0a1b2c"), chat, AgentId("notes"), "notes.wait", "", TEST_TIME.plusSeconds(3600), null)

    private suspend fun jobs(
        arguments: String = "",
        issuer: ChatUser = OPERATOR,
        kind: ChatKind = ChatKind.DIRECT,
    ): String = commands.answer(testCommand("jobs", arguments, issuer = issuer, kind = kind))

    @Test
    fun `jobs lists the jobs of the chat with how long they run and who started them`() = runTest {
        val empty = jobs()
        val elsewhere = JobStatus(RunId("j-x"), testChat("other"), AgentId.MAIN, "fs.read", "", TEST_TIME, null)
        control.jobStatuses = listOf(build, wait, elsewhere)

        assertEquals("No job runs in this chat.", empty)
        assertEquals(
            "Jobs running in this chat (stop one with /jobs stop <id>):\n" +
                "j-7kq2mx [main]: command.exec ./gradlew build, running for 1 h 3 min, started by Operator\n" +
                "j-0a1b2c [notes]: notes.wait, running for 3 min",
            jobs(issuer = GUEST),
        )
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `jobs all lists the jobs of every chat with their chats, for an operator in a direct chat only`() = runTest {
        val elsewhere = JobStatus(RunId("j-x"), testChat("other"), AgentId.MAIN, "fs.read", "", TEST_TIME, null)
        val none = jobs("all")
        control.jobStatuses = listOf(build, elsewhere)

        assertEquals("No job runs in any chat.", none)
        assertEquals(
            "Jobs running in every chat (stop one with /jobs stop <id>):\n" +
                "j-7kq2mx [main] in test:main:chat: command.exec ./gradlew build, running for 1 h 3 min, " +
                "started by Operator\n" +
                "j-x [main] in test:main:other: fs.read, running for 1 h 3 min",
            jobs("ALL"),
        )
        assertEquals(
            "Send /jobs all in a direct chat: here it would show the jobs of other chats to everyone in this one.",
            jobs("all", kind = ChatKind.GROUP),
        )
        assertEquals("Only an operator may list the jobs of every chat.", jobs("all", issuer = GUEST))
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `an operator stops a job of any chat by its id, others only one of this chat`() = runTest {
        val elsewhere = JobStatus(RunId("j-x"), testChat("other"), AgentId.MAIN, "fs.read", "", TEST_TIME, null)
        control.jobStatuses = listOf(build, elsewhere)

        val replies = listOf(
            jobs("stop j-7kq2mx"),
            jobs("STOP j-x"),
            jobs("stop j-nope"),
            jobs("stop j-x", issuer = GUEST),
            jobs("stop j-7kq2mx", issuer = GUEST),
        )

        assertEquals(
            listOf(
                "Job j-7kq2mx is stopped.",
                "Job j-x of test:main:other is stopped.",
                "No job j-nope runs in any chat. Send /jobs all to see the jobs.",
                "No job j-x runs in this chat. Send /jobs to see the jobs.",
                "Job j-7kq2mx is stopped.",
            ),
            replies,
        )
        assertEquals(
            listOf(
                Call.StopJob(null, RunId("j-7kq2mx"), OPERATOR),
                Call.StopJob(null, RunId("j-x"), OPERATOR),
                Call.StopJob(null, RunId("j-nope"), OPERATOR),
                Call.StopJob(chat, RunId("j-x"), GUEST),
                Call.StopJob(chat, RunId("j-7kq2mx"), GUEST),
            ),
            control.calls,
        )
    }

    @Test
    fun `anything but a bare jobs or one stop with an id changes nothing`() = runTest {
        val usage = "Send /jobs to see the jobs running in this chat, /jobs all for those of every chat, or " +
            "/jobs stop <id> to stop one."

        val replies = listOf("stop", "j-7kq2mx", "stop j-1 j-2", "kill j-1").map { jobs(it) }

        assertEquals(List(4) { usage }, replies)
        assertEquals(emptyList(), control.calls)
    }
}
