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
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.sdk.turn.TrustedPerson
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingAgent.Call
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustCommandsTest {
    private val control = RecordingAgent()
    private val commands = TrustCommands(control.conversations, control.trust, commandTexts(), TestClock())

    @Test
    fun `trust lists the trusted folders and trusts the folder it is given`() = runTest {
        assertEquals("No folder is trusted.$PEOPLE", commands.answer(trust()))
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
                "- /srv/my repo (trusted by Operator on 2026-01-01)$PEOPLE",
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
        control.trust.trustFolder(Path.of("/srv/host"), null)

        assertEquals(
            "Trusted folders, each with the folders below it:\n- /srv/config (declared in the config file)\n" +
                "- /srv/host (trusted by the host on 2026-01-01)$PEOPLE",
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
                    DeclaredAndGranted(control.trust, both),
                    commandTexts(),
                    TestClock(),
                )

            assertEquals(
                "Trusted folders, each with the folders below it:\n- /srv/both (declared in the config file, and " +
                    "trusted by Operator on 2026-01-01)$PEOPLE",
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
        control.trust.trustFolder(Path.of("/srv/repo"), OPERATOR)

        assertEquals("/srv/repo is no longer trusted.", commands.answer(untrust("\"/srv/repo\"")))
        assertEquals("/srv/repo is not a trusted folder.", commands.answer(untrust("/srv/repo")))
        assertEquals(Call.UntrustFolder(Path.of("/srv/repo"), OPERATOR), control.calls.last())
    }

    @Test
    fun `a relative or invalid folder changes nothing`() = runTest {
        assertEquals("Give the folder as an absolute path, not ./repo.", commands.answer(trust("./repo")))
        assertEquals("Give the folder as an absolute path, not ~/repo.", commands.answer(untrust("~/repo")))
        assertEquals(
            "Send /trust to see what is trusted, /trust <folder> with the folder's absolute path to trust it, " +
                "/trust <rule>, such as /trust command.exec(git), so that the output of the calls it covers no " +
                "longer taints this conversation, or /trust in reply to someone's message, or naming them with @, " +
                "so that their own messages no longer taint.",
            commands.answer(trust("/srv/\u0000")),
        )
        assertEquals(
            "Send /untrust <folder> with the folder's absolute path, or /untrust <rule> as /trust wrote it, to stop " +
                "trusting it, or /untrust in reply to someone's message, or naming them with @, to stop trusting them.",
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
        assertEquals(CommandForms.ALL, spec.operatorsOnly)
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
                "- mcp.files.* (trusted by Operator on 2026-01-01)$PEOPLE",
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
        control.anyCodeRules += "command.exec(sh)"

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
    fun `only an operator may trust, untrust or untaint, in every form`() {
        val forms = commands.commands.associate { it.name to it.operatorsOnly }

        assertEquals(listOf("trust", "untrust", "untaint").associateWith { CommandForms.ALL }, forms)
    }

    // People.

    @Test
    fun `trust in reply to a message trusts its sender, and untrust in reply stops it`() = runTest {
        val bob = testUser("bob")

        assertEquals(
            "Bob is trusted: their own messages and files no longer taint this agent's conversations, while what " +
                "they forward or quote still does. Trust gives them no authority.",
            commands.answer(testCommand("trust", issuer = OPERATOR, replyTo = bob)),
        )
        assertEquals(
            "Bob is no longer trusted: their messages taint this agent's conversations again.",
            commands.answer(testCommand("untrust", issuer = OPERATOR, replyTo = bob)),
        )
        assertEquals(
            "Bob is not a trusted person.",
            commands.answer(testCommand("untrust", issuer = OPERATOR, replyTo = bob)),
        )
        val chat = trust().chat
        assertEquals(
            listOf(Call.TrustPerson(chat, bob, OPERATOR)) + List(2) { Call.UntrustPerson(chat, bob, OPERATOR) },
            control.calls,
        )
    }

    @Test
    fun `trust naming people trusts each of them, and the list shows them`() = runTest {
        val bob = ChatUser(testUser("bob").address, "Bob", "bob_b", isBot = false, isOperator = false)
        val cy = testUser("cy")

        commands.answer(testCommand("trust", "@bob_b @cy", issuer = OPERATOR, mentions = listOf(bob, cy)))

        assertEquals(
            "No folder is trusted.\n\nPeople whose own messages and files do not taint, trusted here:\n" +
                "- Bob (@bob_b) (trusted by Operator on 2026-01-01)\n- Cy (trusted by Operator on 2026-01-01)\n\n" +
                "People whom their channel's config trusts are marked by the channel and not listed here. Trust " +
                "gives no authority: a trusted person approves nothing and runs no operator commands.",
            commands.answer(trust()),
        )
        assertEquals(listOf(bob, cy), control.calls.map { (it as Call.TrustPerson).person })
    }

    @Test
    fun `untrust of a person whom the channel's config trusts says so, and a refused person replies the refusal`() =
        runTest {
            val configured = testUser("cy", isTrusted = true)

            assertEquals(
                "Their channel's config trusts Cy: edit that config to stop trusting them.",
                commands.answer(testCommand("untrust", "@cy", issuer = OPERATOR, mentions = listOf(configured))),
            )
            control.refuseNext("Operator is an operator, whom the agent trusts already.")
            assertEquals(
                "Operator is an operator, whom the agent trusts already.",
                commands.answer(testCommand("trust", issuer = OPERATOR, replyTo = OPERATOR)),
            )
            assertEquals(emptyList(), control.trust.people(trust().chat))
        }

    @Test
    fun `a person trusted by the host is listed so`() = runTest {
        control.trust.trustPerson(trust().chat, testUser("bob"), null)

        val listed = commands.answer(trust())

        assertEquals("- Bob (trusted by the host on 2026-01-01)", listed.lines()[3])
        assertEquals(TrustedPerson(testUser("bob"), TEST_TIME, null), control.trust.people(trust().chat).single())
    }

    private fun trust(folder: String = "") = testCommand("trust", folder, issuer = OPERATOR)

    private fun untrust(folder: String = "") = testCommand("untrust", folder, issuer = OPERATOR)

    /** [trust] where [folder] is declared and granted, and an untrust drops a grant of a declared folder. */
    @OptIn(InternalAlexandriteApi::class)
    private class DeclaredAndGranted(trust: TrustControl, private val folder: TrustedFolder) : TrustControl by trust {
        override fun folders(): List<TrustedFolder> = listOf(folder)

        override suspend fun untrustFolder(folder: Path, by: ChatUser?): Revocation =
            Revocation(removed = true, declared = true)
    }
}

private const val PEOPLE =
    "\n\nNo person is trusted here.\n\nPeople whom their channel's config trusts are marked by the channel and " +
        "not listed here. Trust gives no authority: a trusted person approves nothing and runs no operator " +
        "commands."
