package org.unitedsoftware.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.CHINESE
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.PLUGIN
import org.unitedsoftware.alexandrite.commands.TestClock
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingAgent.Call
import org.unitedsoftware.alexandrite.testkit.testCommand
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ModeCommandsTest {
    private val clock = TestClock()
    private val control = RecordingAgent(clock = clock).apply { scriptAgent(AgentId.MAIN, "Main") }
    private val commands = ModeCommands(control.settings, commandTexts(), clock)

    private val modes = "Modes: plan, manual, ask, allowedit, auto, bypass."

    private suspend fun mode(arguments: String = ""): String =
        commands.answer(testCommand("mode", arguments, issuer = OPERATOR))

    private fun updates(): List<ChatSettingsUpdate> = control.calls.map { (it as Call.UpdateSettings).update }

    @Test
    fun `mode shows the mode in force and where it is set`() = runTest {
        assertEquals("Permission mode: ask (the default). $modes", mode())
        control.scriptAgent(AgentId.MAIN, "Main", mode = PermissionMode.MANUAL)
        assertEquals("Permission mode: manual (the agent's setting). $modes", mode())
        mode("allowedit")
        assertEquals("Permission mode: allowedit (set in this chat). $modes", mode())
    }

    @Test
    fun `mode sets each mode, given in any case, and reset goes back to the agent's`() = runTest {
        control.scriptAgent(AgentId.MAIN, "Main", mode = PermissionMode.AUTO)

        assertEquals("The permission mode of this chat is now plan.", mode("Plan"))
        assertEquals("The permission mode of this chat is now manual.", mode("manual"))
        assertEquals("The permission mode is auto again (the agent's setting).", mode("reset"))
        assertEquals(
            listOf(
                ChatSettingsUpdate.builder().mode(PermissionMode.PLAN).build(),
                ChatSettingsUpdate.builder().mode(PermissionMode.MANUAL).build(),
                ChatSettingsUpdate.builder().resetMode().build(),
            ),
            updates(),
        )
    }

    @Test
    fun `a bypass lasts an hour, or the duration given, and shows the time left and what follows it`() = runTest {
        control.scriptAgent(AgentId.MAIN, "Main", mode = PermissionMode.ALLOW_EDIT)

        val set = "The permission mode of this chat is now bypass"
        assertEquals("$set for 1 h, then allowedit.", mode("bypass"))
        clock.now += Duration.ofMinutes(20)
        assertEquals("Permission mode: bypass (set in this chat) for 40 min more, then allowedit. $modes", mode())
        val again = clock.now
        assertEquals("$set for 2 h 30 min, then allowedit.", mode("bypass 150m"))
        clock.now += Duration.ofMinutes(150)
        assertEquals("Permission mode: allowedit (the agent's setting). $modes", mode())
        assertEquals(
            listOf(
                ChatSettingsUpdate.builder().mode(PermissionMode.BYPASS).build(),
                ChatSettingsUpdate.builder().mode(PermissionMode.BYPASS, again + Duration.ofMinutes(150)).build(),
            ),
            updates(),
        )
    }

    @Test
    fun `a bypass longer than a day lasts a day, and the reply says so`() = runTest {
        val start = clock.now

        assertEquals(
            "The permission mode of this chat is now bypass for 24 h, then ask.\nA bypass lasts 24 h at most.",
            mode("bypass 3d"),
        )
        assertEquals(
            "The permission mode of this chat is now bypass for 24 h, then ask.",
            mode("bypass 24H"),
        )
        assertEquals(
            List(2) { ChatSettingsUpdate.builder().mode(PermissionMode.BYPASS, start + Duration.ofDays(1)).build() },
            updates(),
        )
    }

    @Test
    fun `an unknown mode, a duration of another mode, a malformed duration or more words change nothing`() = runTest {
        assertEquals("'yolo' is no permission mode. $modes", mode("yolo"))
        assertEquals("'2h' is no permission mode. $modes", mode("2h"))
        val usage = "Send /mode to see the permission mode, /mode <mode> to set it, /mode bypass <duration> " +
            "(such as 30m, 2h or 1d) for a bypass that ends then, or /mode reset to go back to the default."
        assertEquals(usage, mode("plan 2h"))
        assertEquals(usage, mode("bypass 2h now"))
        assertEquals(usage, mode("reset 2h"))
        for (duration in listOf("soon", "0m", "2", "1w", "-1h")) {
            assertEquals("'$duration' is no duration. Give one such as 30m, 2h or 1d.", mode("bypass $duration"))
        }
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `a refused change is the reply`() = runTest {
        control.refuseNext("No agent serves test:main:chat.")

        assertEquals("No agent serves test:main:chat.", mode("plan"))
    }

    @Test
    fun `a change replies in the language the chat gets`() = runTest {
        val catalog = TextCatalog.builder()
            .text(PLUGIN, CHINESE, "mode.set", "权限模式现在是 {mode}。")
            .text(PLUGIN, CHINESE, "duration.hours", "{hours} 小时")
            .text(PLUGIN, CHINESE, "mode.set_bypass", "权限模式现在是 bypass，持续 {left}，之后为 {after}。")
            .build()
        val commands = ModeCommands(control.settings, commandTexts(catalog), clock)
        control.settings.update(
            testCommand("mode").chat,
            ChatSettingsUpdate.builder().language(CHINESE).build(),
            OPERATOR,
        )

        assertEquals("权限模式现在是 auto。", commands.answer(testCommand("mode", "auto")))
        assertEquals("权限模式现在是 bypass，持续 2 小时，之后为 ask。", commands.answer(testCommand("mode", "bypass 2h")))
    }
}
