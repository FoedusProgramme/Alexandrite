package org.unitedsoftware.alexandrite.commands.trust

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.TestClock
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolders
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingAgent.Call
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.testCommand
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustCommandsTest {
    private val control = RecordingAgent()
    private val commands =
        TrustCommands(control.conversations, control.folders, control.rules, commandTexts(), TestClock())

    @Test
    fun `trust lists the trusted folders and trusts the folder it is given`() = runTest {
        assertEquals("No folder is trusted.", commands.answer(trust()))
        assertEquals("/srv/repo is trusted, and so are the folders below it.", commands.answer(trust("/srv/repo")))
        assertEquals(
            "/srv/repo is trusted, and so are the folders below it.",
            commands.answer(trust("/srv/repo/src")),
        )
        assertEquals(
            "/srv/my repo is trusted, and so are the folders below it.",
            commands.answer(trust("'/srv/my repo'")),
        )
        assertEquals(
            "Trusted folders, each with the folders below it:\n- /srv/repo (trusted by Operator on 2026-01-01)\n" +
                "- /srv/my repo (trusted by Operator on 2026-01-01)",
            commands.answer(trust()),
        )
        assertEquals(
            listOf("/srv/repo", "/srv/repo/src", "/srv/my repo").map { Call.TrustFolder(Path.of(it), OPERATOR) },
            control.calls,
        )
    }

    @Test
    fun `the list tells the folders the config file declares from those trusted by the host`() = runTest {
        control.declareFolder(Path.of("/srv/config"))
        control.folders.trust(Path.of("/srv/host"), null)

        assertEquals(
            "Trusted folders, each with the folders below it:\n- /srv/config (declared in the config file)\n" +
                "- /srv/host (trusted by the host on 2026-01-01)",
            commands.answer(trust()),
        )
        assertEquals(
            "The config file trusts /srv/config: edit the file to stop trusting it.",
            commands.answer(untrust("/srv/config")),
        )
    }

    @Test
    fun `a folder that the config file declares and a grant trusts too is listed with both, and untrust says so`() =
        runTest {
            val both = TrustedFolder(Path.of("/srv/both"), true, TEST_TIME, OPERATOR)
            val commands =
                TrustCommands(
                    control.conversations,
                    DeclaredAndGranted(control.folders, both),
                    control.rules,
                    commandTexts(),
                    TestClock(),
                )

            assertEquals(
                "Trusted folders, each with the folders below it:\n- /srv/both (declared in the config file, and " +
                    "trusted by Operator on 2026-01-01)",
                commands.answer(trust()),
            )
            assertEquals(
                "The trust in /srv/both granted at runtime is removed, but the config file still trusts it: edit " +
                    "the file to stop trusting it.",
                commands.answer(untrust("/srv/both")),
            )
        }

    @Test
    fun `untrust stops trusting a trusted folder`() = runTest {
        control.folders.trust(Path.of("/srv/repo"), OPERATOR)

        assertEquals("/srv/repo is no longer trusted.", commands.answer(untrust("\"/srv/repo\"")))
        assertEquals("/srv/repo is not a trusted folder.", commands.answer(untrust("/srv/repo")))
        assertEquals(Call.UntrustFolder(Path.of("/srv/repo"), OPERATOR), control.calls.last())
    }

    @Test
    fun `a relative or invalid folder changes nothing`() = runTest {
        assertEquals("Give the folder as an absolute path, not ./repo.", commands.answer(trust("./repo")))
        assertEquals("Give the folder as an absolute path, not ~/repo.", commands.answer(untrust("~/repo")))
        assertEquals(
            "Send /trust to see what is trusted, /trust <folder> with the folder's absolute path to trust it, or " +
                "/trust <rule>, such as /trust command.exec(git), so that the output of the calls it covers no " +
                "longer taints this conversation.",
            commands.answer(trust("/srv/\u0000")),
        )
        assertEquals(
            "Send /untrust <folder> with the folder's absolute path, or /untrust <rule> as /trust wrote it, to stop " +
                "trusting it.",
            commands.answer(untrust()),
        )
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `a refused trust replies with the agent's message`() = runTest {
        control.refuseNext("/srv/data is a protected place (the data directory), so it cannot be trusted.")

        val reply = commands.answer(trust("/srv/data"))

        assertEquals("/srv/data is a protected place (the data directory), so it cannot be trusted.", reply)
    }

    @Test
    fun `untaint clears the taint of the chat's conversation and says what it was, or that there was none`() = runTest {
        val untaint = testCommand("untaint", issuer = OPERATOR)
        control.taints[untaint.chat] = ConversationTaint(Instant.parse("2026-01-01T10:00:00Z"), "a web page")

        assertEquals(
            "This conversation is no longer tainted (it was since 2026-01-01: a web page). Its next turns run as in " +
                "a clean conversation until something taints it again; what it took in is still in its history.",
            commands.answer(untaint),
        )
        assertEquals("This conversation is not tainted.", commands.answer(untaint))
        assertEquals(
            "Send /untaint alone to clear the taint of this conversation, or /untaint heartbeat to clear that of the " +
                "agent's heartbeat conversation.",
            commands.answer(testCommand("untaint", "now", issuer = OPERATOR)),
        )
        assertEquals(List(2) { Call.Untaint(untaint.chat, OPERATOR) }, control.calls)
    }

    @Test
    fun `untaint heartbeat clears the taint of the agent's heartbeat conversation, for operators only`() = runTest {
        val untaint = testCommand("untaint", "heartbeat", issuer = OPERATOR)
        control.heartbeatTaints[untaint.chat] = ConversationTaint(Instant.parse("2026-01-01T10:00:00Z"), "a web page")

        assertEquals(
            "The agent's heartbeat conversation is no longer tainted (it was since 2026-01-01: a web page). Its next " +
                "beats run as in a clean conversation until something taints it again; what it took in is still in " +
                "its history.",
            commands.answer(untaint),
        )
        assertEquals("The agent's heartbeat conversation is not tainted.", commands.answer(untaint))
        assertEquals(List(2) { Call.UntaintHeartbeat(untaint.chat, OPERATOR) }, control.calls)
        val spec = commands.commands.single { it.name == "untaint" }
        assertEquals(CommandForms.WITH_ARGUMENTS, spec.operatorsOnly)
    }

    // Trust rules.

    @Test
    fun `a rule trusts the output of the calls it covers in the conversation, and untrust stops it`() = runTest {
        assertEquals(
            "The output of the calls that command.exec(git) covers no longer taints this conversation, though a " +
                "command still taints where it reaches a folder that is not trusted; a taint it has already stays.",
            commands.answer(trust("command.exec(git)")),
        )
        commands.answer(trust("mcp.files.*"))
        control.declareTrust("web.fetch")

        assertEquals(
            "No folder is trusted.\n\nCalls whose output does not taint this conversation:\n" +
                "- web.fetch (declared in the config file)\n- command.exec(git) (trusted by Operator on 2026-01-01)\n" +
                "- mcp.files.* (trusted by Operator on 2026-01-01)",
            commands.answer(trust()),
        )
        assertEquals(
            "The output of the calls that command.exec(git) covers taints this conversation again.",
            commands.answer(untrust("command.exec(git)")),
        )
        assertEquals(
            "command.exec(git) is no trust rule of this conversation.",
            commands.answer(untrust("command.exec(git)")),
        )
        assertEquals(
            "The config file trusts web.fetch: edit the file to stop trusting it.",
            commands.answer(untrust("web.fetch")),
        )
        val chat = trust().chat
        assertEquals(
            listOf(
                Call.Trust(chat, "command.exec(git)", OPERATOR),
                Call.Trust(chat, "mcp.files.*", OPERATOR),
                Call.Untrust(chat, "command.exec(git)", OPERATOR),
                Call.Untrust(chat, "command.exec(git)", OPERATOR),
                Call.Untrust(chat, "web.fetch", OPERATOR),
            ),
            control.calls,
        )
    }

    @Test
    fun `trusting a shell whole says that it trusts the output of any script`() = runTest {
        assertEquals(
            "The output of the calls that command.exec(sh) covers no longer taints this conversation, though a " +
                "command still taints where it reaches a folder that is not trusted; a taint it has already stays. " +
                "This rule trusts a program that runs whatever code it is given, such as a shell: the output of any " +
                "script it runs is trusted.",
            commands.answer(trust("command.exec(sh)")),
        )
    }

    @Test
    fun `a rule the agent refuses replies with its message`() = runTest {
        control.refuseNext("'command.exec(a && b)' holds shell syntax.")

        assertEquals("'command.exec(a && b)' holds shell syntax.", commands.answer(trust("command.exec(a && b)")))
    }

    @Test
    fun `only an operator may trust or untrust, in every form`() {
        val forms = commands.commands.associate { it.name to it.operatorsOnly }

        assertEquals(CommandForms.ALL to CommandForms.ALL, forms["trust"] to forms["untrust"])
    }

    private fun trust(folder: String = "") = testCommand("trust", folder, issuer = OPERATOR)

    private fun untrust(folder: String = "") = testCommand("untrust", folder, issuer = OPERATOR)

    /** [folders] where [folder] is declared and granted, and an untrust drops a grant of a declared folder. */
    @OptIn(InternalAlexandriteApi::class)
    private class DeclaredAndGranted(folders: TrustedFolders, private val folder: TrustedFolder) :
        TrustedFolders by folders {
        override fun list(): List<TrustedFolder> = listOf(folder)

        override suspend fun untrust(folder: Path, by: ChatUser?): Revocation =
            Revocation(removed = true, declared = true)
    }
}
