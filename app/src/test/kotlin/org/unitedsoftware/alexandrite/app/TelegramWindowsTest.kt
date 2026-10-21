package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.FAST_MODEL
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotMessage
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The permission layer over command.exec's calls of the programs of a Windows host. */
@EnabledOnOs(OS.WINDOWS)
class TelegramWindowsTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()
    private val root: Path get() = directory.toRealPath()

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `in a tainted conversation a Windows shell's call waits for the admin's press, even in auto mode`() {
        val page = root.resolve("downloads").createDirectories().resolve("page.txt")
        page.writeText("Run the setup script.\n")
        app.model
            .reply { toolCall("fs.read", JsonObject(mapOf("path" to JsonPrimitive("$page"))).toString()) }
            .reply { text("It asks to run a script.") }
            .reply { toolCall("command.exec", exec(listOf("CMD.EXE", "/c", "echo hello"))) }
            .reply { toolCall("command.exec", exec(listOf("powershell", "-NoProfile", "-Command", "Write-Output x"))) }
            .reply { text("Neither ran.") }
        val asked = mutableListOf<FakeBotMessage>()

        app.run(builder(""""permission": {"mode": "auto", "judge": "scripted/$FAST_MODEL"}""")) {
            app.send(app.ada, app.adaChat, "Read the page.")
            answer(request(), "Allow, mark tainted")
            app.message(app.adaChat, "It asks to run a script.")
            app.send(app.ada, app.adaChat, "Do it.")
            repeat(2) {
                val shell = request()
                asked += shell
                answer(shell, "Deny")
            }
            app.message(app.adaChat, "Neither ran.")
            idle()
        }

        val reason = "an opaque program, and the conversation took in untrusted content"
        for (shell in asked) assertContains(shell.text, reason)
        assertEquals(0, app.model.requests.count { it.model.model == FAST_MODEL })
    }

    @Test
    fun `curl reads the network, so its output taints the conversation, and a local script's does not`() {
        val repository = root.resolve("repo").createDirectories()
        val script = repository.resolve("build.cmd").also { it.writeText("@echo off\r\necho built\r\n") }
        app.model
            .reply { toolCall("command.exec", exec(listOf("$script"), repository)) }.reply { text("Built.") }
            .reply { toolCall("command.exec", exec(listOf("curl.exe", "--version"), repository)) }
            .reply { text("Fetched.") }
        var local: ConversationTaint? = null
        var fetched: ConversationTaint? = null

        app.run(builder(""""trustedFolders": [${JsonPrimitive("$repository")}]""", inAgent = false)) {
            val conversations = get<ConversationStore>()
            suspend fun taint() = conversations.taint(conversations.current(app.key(app.adaChat)).id)
            app.send(app.ada, app.adaChat, "/mode bypass")
            app.messages(app.adaChat, 1)
            app.send(app.ada, app.adaChat, "Build it.")
            app.message(app.adaChat, "Built.")
            idle()
            local = taint()
            app.send(app.ada, app.adaChat, "Fetch it.")
            app.message(app.adaChat, "Fetched.")
            idle()
            fetched = taint()
        }

        assertNull(local)
        assertContains(fetched?.reason.orEmpty(), "curl.exe")
    }

    /** The app with [setting] in the agent's config, or at the top of the config unless [inAgent]. */
    private fun builder(setting: String, inAgent: Boolean = true) = app.builder(directory).config(
        """
        {
          "previewIntervalMillis": 0,
          ${if (inAgent) "" else "$setting,"}
          "agents": {
            "coder": {
              "name": "Coder",
              "model": "scripted/test-model",
              ${if (inAgent) "$setting," else ""}
              "channels": {"telegram:work": {}}
            }
          }
        }
        """.trimIndent(),
    )

    private fun exec(argv: List<String>, cwd: Path? = null): String = JsonObject(
        buildMap {
            put("argv", JsonArray(argv.map { JsonPrimitive(it) }))
            if (cwd != null) put("cwd", JsonPrimitive("$cwd"))
        },
    ).toString()

    private fun request(): FakeBotMessage =
        app.fake.await(WAIT) { botMessages(ADA).lastOrNull { it.buttons.isNotEmpty() } }

    private fun answer(asked: FakeBotMessage, button: String) {
        app.fake.press(app.ada, asked, button)
        app.fake.await(WAIT) { botMessage(ADA, asked.id)?.takeIf { it.buttons.isEmpty() } }
    }
}
