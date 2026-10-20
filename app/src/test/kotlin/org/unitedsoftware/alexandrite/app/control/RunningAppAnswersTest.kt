package org.unitedsoftware.alexandrite.app.control

import kotlinx.serialization.json.JsonElement
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

    /** A config that the requests of these tests never reach. */
    private object UnreachedConfig : HostConfig {
        override suspend fun get(path: String): JsonElement? = null

        override suspend fun edit(block: ConfigEdit.() -> Unit): ConfigChanges = error("Not reached.")

        override suspend fun replace(text: String): ConfigChanges = error("Not reached.")
    }

    private class App(private val host: HostConfig?, override val configFile: Path?) : RunningApp {
        override fun status() = ControlReply.Status("1.2.3", 7, Instant.EPOCH, null, "/srv/data")

        override fun stop() {}

        override fun config(): HostConfig? = host

        override fun mcp(): McpControl? = null
    }

    private fun serve(app: App?, block: suspend (Path) -> Unit) =
        served(directory, ControlAnswers("1.2.3", app = app) { PairingDesks(emptyList()) }, block)

    @Test
    fun `without a config file a change is refused, and without the MCP plugin a login`() {
        serve(App(UnreachedConfig, configFile = null)) { socket ->
            val refused = assertIs<ControlReply.ConfigRefused>(ask(socket, ControlRequest.ReplaceConfig("{}")))
            val mcp = assertIs<ControlReply.Failure>(ask(socket, ControlRequest.McpLogin("docs")))

            assertEquals("no_file", refused.refusal)
            assertEquals("The MCP plugin does not run in this Alexandrite.", mcp.message)
        }
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
}
