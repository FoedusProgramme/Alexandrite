package org.foedusprogramme.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.commands.answer
import org.foedusprogramme.alexandrite.commands.commandTexts
import org.foedusprogramme.alexandrite.sdk.agent.AgentProfile
import org.foedusprogramme.alexandrite.sdk.agent.ProfileSource
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.testCommand
import org.foedusprogramme.alexandrite.testkit.testUser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileCommandsTest {
    private val control = RecordingAgentControl()
    private val commands = ProfileCommands(control, commandTexts())

    @Test
    fun `without a profile it says so`() = runTest {
        assertEquals("No profile exists yet.", commands.answer(testCommand("profiles")))
    }

    @Test
    fun `it lists each profile's id, name, description, model, mode and source`() = runTest {
        control.scriptProfile(
            AgentProfile(AgentId("reviewer"), "Reviewer", "Reviews code.", "Be strict."),
            ProfileSource.Config,
        )
        control.scriptProfile(
            AgentProfile(
                AgentId("writer"),
                "Writer",
                "Writes docs.",
                "Be clear.",
                model = ModelRef.parse("anthropic/claude-haiku-5-5"),
                mode = PermissionMode.ASK,
            ),
            ProfileSource.Plugin,
        )
        control.scriptProfile(
            AgentProfile(AgentId("helper"), "Helper", "Helps.", "Be kind."),
            ProfileSource.Created(testUser("ada", isAdmin = true), Instant.parse("2026-10-01T00:00:00Z")),
        )

        assertEquals(
            """
            Profiles a delegating agent may run as a sub-agent:
            reviewer "Reviewer": Reviews code. (model: the delegating agent's, mode: tightens whatever mode it runs in, source: config)
            writer "Writer": Writes docs. (model: anthropic/claude-haiku-5-5, mode: ask, source: plugin)
            helper "Helper": Helps. (model: the delegating agent's, mode: tightens whatever mode it runs in, source: created by Ada)
            """.trimIndent(),
            commands.answer(testCommand("profiles")),
        )
    }

    @Test
    fun `a profile created by the host is shown as such`() = runTest {
        control.scriptProfile(
            AgentProfile(AgentId("helper"), "Helper", "Helps.", "Be kind."),
            ProfileSource.Created(null, Instant.parse("2026-10-01T00:00:00Z")),
        )

        assertTrue("created by the host" in commands.answer(testCommand("profiles")))
    }

    @Test
    fun `arguments are refused with the usage line`() = runTest {
        assertEquals(
            "Send /profiles without anything after it.",
            commands.answer(testCommand("profiles", "reviewer")),
        )
    }
}
