package org.foedusprogramme.alexandrite.commands.trust

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.commands.ADMIN
import org.foedusprogramme.alexandrite.commands.answer
import org.foedusprogramme.alexandrite.commands.commandTexts
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl.Call
import org.foedusprogramme.alexandrite.testkit.testCommand
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustCommandsTest {
    private val control = RecordingAgentControl()
    private val commands = TrustCommands(control, commandTexts())

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
            "Trusted folders, each with the folders below it:\n- /srv/repo\n- /srv/my repo",
            commands.answer(trust()),
        )
        assertEquals(
            listOf("/srv/repo", "/srv/repo/src", "/srv/my repo").map { Call.TrustFolder(Path.of(it), ADMIN) },
            control.calls,
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
        val refusing = object : AgentControl by control {
            override suspend fun trustFolder(folder: Path, by: ChatUser?): Path = throw IllegalArgumentException(
                "$folder is a protected place (the data directory), so it cannot be trusted.",
            )
        }

        val reply = TrustCommands(refusing, commandTexts()).answer(trust("/srv/data"))

        assertEquals("/srv/data is a protected place (the data directory), so it cannot be trusted.", reply)
    }

    private fun trust(folder: String = "") = testCommand("trust", folder, issuer = ADMIN)

    private fun untrust(folder: String = "") = testCommand("untrust", folder, issuer = ADMIN)
}
