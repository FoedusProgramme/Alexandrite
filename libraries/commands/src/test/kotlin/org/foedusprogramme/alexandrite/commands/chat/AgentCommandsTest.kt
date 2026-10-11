package org.foedusprogramme.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.commands.ADMIN
import org.foedusprogramme.alexandrite.commands.CHINESE
import org.foedusprogramme.alexandrite.commands.PLUGIN
import org.foedusprogramme.alexandrite.commands.answer
import org.foedusprogramme.alexandrite.commands.commandTexts
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl.Call
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentCommandsTest {
    private val control = RecordingAgentControl().apply {
        scriptAgent(AgentId.MAIN, "Main")
        scriptAgent(CODER, "Coder", "Writes code")
    }
    private val commands = AgentCommands(control, commandTexts())
    private val chat = testChat()

    // /agent.

    @Test
    fun `agent shows the chat's agent, why it serves the chat and the agents to choose from`() = runTest {
        assertEquals(
            """
            This chat talks to main "Main": the default agent of test:main.
            The agents of this channel (switch with /agent <id>):
            - main "Main" (default)
            - coder "Coder": Writes code
            """.trimIndent(),
            commands.answer(testCommand("agent")),
        )
        commands.answer(testCommand("agent", "coder"))
        assertEquals(
            "This chat talks to coder \"Coder\": chosen here with /agent.",
            commands.answer(testCommand("agent")).lines().first(),
        )
    }

    @Test
    fun `agent with an id switches the chat to that agent`() = runTest {
        val reply = commands.answer(testCommand("agent", "coder", issuer = ADMIN))

        assertEquals("This chat now talks to coder \"Coder\". The conversation with the agent before is kept.", reply)
        assertEquals(CODER, control.settings(chat).agent)
        assertEquals(
            listOf(Call.UpdateSettings(chat, ChatSettingsUpdate.builder().agent(CODER).build(), ADMIN)),
            control.calls,
        )
    }

    @Test
    fun `a switch replies in the language of the agent the chat switched to`() = runTest {
        control.scriptAgent(CODER, "Coder", language = CHINESE)
        val catalog = TextCatalog.builder().text(PLUGIN, CHINESE, "agent.switched", "现在由 {agent}「{name}」回答。").build()

        val reply = AgentCommands(control, commandTexts(catalog)).answer(testCommand("agent", "coder"))

        assertEquals("现在由 coder「Coder」回答。", reply)
    }

    @Test
    fun `agent reset goes back to the agent that serves the chat by default`() = runTest {
        commands.answer(testCommand("agent", "coder"))

        val reply = commands.answer(testCommand("agent", "RESET"))

        assertEquals("This chat talks to main again: the default agent of test:main.", reply)
        assertEquals(
            ChatSettingsUpdate.builder().resetAgent().build(),
            (control.calls.last() as Call.UpdateSettings).update,
        )
    }

    @Test
    fun `a malformed agent id or more than one argument changes nothing`() = runTest {
        assertEquals(
            "'Coder' is no agent id. Agent ids are lowercase words joined by hyphens, such as main.",
            commands.answer(testCommand("agent", "Coder")),
        )
        assertEquals(
            "Send /agent to see the agents, /agent <id> to switch to one, or /agent reset to go back to the default.",
            commands.answer(testCommand("agent", "coder now")),
        )
        assertEquals(emptyList(), control.calls)
    }

    // /home.

    @Test
    fun `home makes the chat the home chat of its agent, and home off stops it being one`() = runTest {
        assertEquals("This chat is not the home chat of main.", commands.answer(testCommand("home", "off")))
        assertEquals(
            "This chat is now the home chat of main on test:main.",
            commands.answer(testCommand("home", issuer = ADMIN)),
        )
        assertEquals(
            "This chat talks to main \"Main\": this is its home chat.",
            commands.answer(testCommand("agent")).lines().first(),
        )
        assertEquals(
            "This chat is no longer the home chat of main.",
            commands.answer(testCommand("home", "OFF", issuer = ADMIN)),
        )
        assertEquals(
            listOf(
                Call.UpdateSettings(chat, ChatSettingsUpdate.builder().home().build(), ADMIN),
                Call.UpdateSettings(chat, ChatSettingsUpdate.builder().noHome().build(), ADMIN),
            ),
            control.calls,
        )
    }

    @Test
    fun `home reset goes back to the configured home chat`() = runTest {
        val reply = commands.answer(testCommand("home", "reset", issuer = ADMIN))

        assertEquals("main uses its configured home chat on test:main again.", reply)
        assertEquals(
            listOf(Call.UpdateSettings(chat, ChatSettingsUpdate.builder().resetHome().build(), ADMIN)),
            control.calls,
        )
    }

    @Test
    fun `a refused home replies with the agent's message`() = runTest {
        control.updateSettings(chat, ChatSettingsUpdate.builder().agent(CODER).home().build(), ADMIN)
        control.updateSettings(chat, ChatSettingsUpdate.builder().agent(AgentId.MAIN).build(), ADMIN)

        val reply = commands.answer(testCommand("home"))

        assertEquals("test:main:chat is the home chat of agent 'coder'.", reply)
    }

    @Test
    fun `home takes nothing but off and reset`() = runTest {
        val reply = commands.answer(testCommand("home", "away"))

        assertEquals(
            "Send /home to make this chat the agent's home chat, /home off to stop it being one, " +
                "or /home reset to go back to the configured home chat.",
            reply,
        )
        assertEquals(emptyList(), control.calls)
    }

    private companion object {
        val CODER = AgentId("coder")
    }
}
