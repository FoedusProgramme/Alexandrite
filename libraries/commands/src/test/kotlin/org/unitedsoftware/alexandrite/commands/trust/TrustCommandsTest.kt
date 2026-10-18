package org.unitedsoftware.alexandrite.commands.trust

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.ADMIN
import org.unitedsoftware.alexandrite.commands.TestClock
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.testkit.RecordingAgentControl
import org.unitedsoftware.alexandrite.testkit.RecordingAgentControl.Call
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.testCommand
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustCommandsTest {
    private val control = RecordingAgentControl()
    private val commands = TrustCommands(control, commandTexts(), TestClock())

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
            "Trusted folders, each with the folders below it:\n- /srv/repo (trusted by Admin on 2026-01-01)\n" +
                "- /srv/my repo (trusted by Admin on 2026-01-01)",
            commands.answer(trust()),
        )
        assertEquals(
            listOf("/srv/repo", "/srv/repo/src", "/srv/my repo").map { Call.TrustFolder(Path.of(it), ADMIN) },
            control.calls,
        )
    }

    @Test
    fun `the list tells the folders the config file declares from those trusted by the host`() = runTest {
        control.declareFolder(Path.of("/srv/config"))
        control.trustFolder(Path.of("/srv/host"), null)

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
            val both = TrustedFolder(Path.of("/srv/both"), true, TEST_TIME, ADMIN)
            val commands = TrustCommands(DeclaredAndGranted(control, both), commandTexts(), TestClock())

            assertEquals(
                "Trusted folders, each with the folders below it:\n- /srv/both (declared in the config file, and " +
                    "trusted by Admin on 2026-01-01)",
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
        control.trustFolder(Path.of("/srv/repo"), ADMIN)

        assertEquals("/srv/repo is no longer trusted.", commands.answer(untrust("\"/srv/repo\"")))
        assertEquals("/srv/repo is not a trusted folder.", commands.answer(untrust("/srv/repo")))
        assertEquals(Call.UntrustFolder(Path.of("/srv/repo"), ADMIN), control.calls.last())
    }

    @Test
    fun `a relative or invalid folder changes nothing`() = runTest {
        assertEquals("Give the folder as an absolute path, not repo.", commands.answer(trust("repo")))
        assertEquals("Give the folder as an absolute path, not ~/repo.", commands.answer(untrust("~/repo")))
        assertEquals(
            "Send /trust to see the trusted folders, or /trust <folder> with the folder's absolute path to trust it.",
            commands.answer(trust("/srv/\u0000")),
        )
        assertEquals(
            "Send /untrust <folder> with the folder's absolute path to stop trusting it.",
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
        val untaint = testCommand("untaint", issuer = ADMIN)
        control.taints[untaint.chat] = ConversationTaint(Instant.parse("2026-01-01T10:00:00Z"), "a web page")

        assertEquals(
            "This conversation is no longer tainted (it was since 2026-01-01: a web page). Its next turns run as in " +
                "a clean conversation until something taints it again; what it took in is still in its history.",
            commands.answer(untaint),
        )
        assertEquals("This conversation is not tainted.", commands.answer(untaint))
        assertEquals(
            "Send /untaint alone to clear the taint of this conversation.",
            commands.answer(testCommand("untaint", "now", issuer = ADMIN)),
        )
        assertEquals(List(2) { Call.Untaint(untaint.chat, ADMIN) }, control.calls)
    }

    private fun trust(folder: String = "") = testCommand("trust", folder, issuer = ADMIN)

    private fun untrust(folder: String = "") = testCommand("untrust", folder, issuer = ADMIN)

    /** [control] where [folder] is declared and granted, and an untrust drops a grant of a declared folder. */
    @OptIn(InternalAlexandriteApi::class)
    private class DeclaredAndGranted(control: AgentControl, private val folder: TrustedFolder) :
        AgentControl by control {
        override fun trustedFolders(): List<TrustedFolder> = listOf(folder)

        override suspend fun untrustFolder(folder: Path, by: ChatUser?): Revocation =
            Revocation(removed = true, declared = true)
    }
}
