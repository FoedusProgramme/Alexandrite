package org.unitedsoftware.alexandrite.app.control

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesks
import org.unitedsoftware.alexandrite.mcp.McpControl
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The control requests that the running app answers besides the pairings. */
class RunningAppAnswersTest {
    @TempDir
    lateinit var directory: Path

    /** A config that records the changes it is given and refuses a path named `refused`. */
    private class RecordingConfig : HostConfig {
        val changes = mutableListOf<String>()

        override suspend fun get(path: String): JsonElement? = null

        override suspend fun edit(block: ConfigEdit.() -> Unit): ConfigChanges {
            val edit = object : ConfigEdit {
                override fun get(path: String): JsonElement? = null

                override fun set(path: String, value: JsonElement) {
                    changes += "set $path $value"
                }

                override fun remove(path: String) {
                    changes += "remove $path"
                }
            }
            edit.block()
            return ConfigChanges(changes.map { it.split(' ')[1] }, emptyList())
        }

        override suspend fun replace(text: String): ConfigChanges {
            changes += "replace $text"
            return ConfigChanges(listOf("app.zone"), listOf("app.zone"))
        }
    }

    private class App(private val host: HostConfig?, override val configFile: Path?) : RunningApp {
        var stops = 0

        override fun status() = ControlReply.Status("1.2.3", 7, Instant.EPOCH, null, "/srv/data")

        override fun stop() {
            stops++
        }

        override fun config(): HostConfig? = host

        override fun mcp(): McpControl? = null
    }

    private fun serve(app: App?, block: suspend (Path) -> Unit) =
        served(directory, ControlAnswers("1.2.3", app = app) { PairingDesks(emptyList()) }, block)

    @Test
    fun `a change is made by the app's config and answered with what changed`() {
        val config = RecordingConfig()
        val app = App(config, Path.of("/srv/alexandrite.json"))

        serve(app) { socket ->
            val changes = listOf(
                ConfigChangeView("app.zone", JsonPrimitive("UTC")),
                ConfigChangeView("app.language", remove = true),
            )
            val changed = assertIs<ControlReply.ConfigChanged>(ask(socket, ControlRequest.ChangeConfig(changes)))
            val replaced = assertIs<ControlReply.ConfigChanged>(ask(socket, ControlRequest.ReplaceConfig("{}")))

            assertEquals("/srv/alexandrite.json", changed.file)
            assertEquals(listOf("app.zone", "app.language"), changed.changed)
            assertEquals(listOf("app.zone"), replaced.restart)
        }
        assertEquals(listOf("set app.zone \"UTC\"", "remove app.language", "replace {}"), config.changes)
    }

    @Test
    fun `without a config file a change is refused, and without the MCP plugin a login`() {
        serve(App(RecordingConfig(), configFile = null)) { socket ->
            val refused = assertIs<ControlReply.ConfigRefused>(ask(socket, ControlRequest.ReplaceConfig("{}")))
            val mcp = assertIs<ControlReply.Failure>(ask(socket, ControlRequest.McpLogin("docs")))

            assertEquals("no_file", refused.refusal)
            assertEquals("The MCP plugin does not run in this Alexandrite.", mcp.message)
        }
    }

    @Test
    fun `the app answers its status and stops when asked`() {
        val app = App(null, null)

        serve(app) { socket ->
            assertEquals(7, assertIs<ControlReply.Status>(ask(socket, ControlRequest.Status)).pid)
            assertIs<ControlReply.Stopping>(ask(socket, ControlRequest.Stop))
        }
        assertEquals(1, app.stops)
    }

    @Test
    fun `an app that gives the socket nothing beyond its pairings says so`() {
        serve(null) { socket ->
            val status = assertIs<ControlReply.Failure>(ask(socket, ControlRequest.Status))
            val chats = ask(socket, ControlRequest.Chats)

            assertEquals("This Alexandrite does not answer that request.", status.message)
            assertIs<ControlReply.NoDesk>(chats)
        }
    }

    @Test
    fun `the chats are the paired ones with the agent that serves each`() {
        served(directory, FakeDesk(paired = listOf(declared("7")))) { socket ->
            val chats = assertIs<ControlReply.Chats>(ask(socket, ControlRequest.Chats))

            assertEquals(listOf("chat:work:7"), chats.chats.map { it.chat })
            assertEquals(listOf(null), chats.chats.map { it.agent })
        }
    }
}
