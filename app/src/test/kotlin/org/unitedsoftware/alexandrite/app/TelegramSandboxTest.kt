package org.unitedsoftware.alexandrite.app

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.sandbox.seatbelt.AlexandriteSandboxSeatbeltIndex
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** command.exec in an agent's Seatbelt sandbox, end to end in `ask` mode. */
@EnabledOnOs(OS.MAC)
class TelegramSandboxTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        createContext("/hello") { exchange ->
            val body = "hello through the proxy".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    @AfterTest
    fun close() {
        server.stop(0)
        app.close()
    }

    @Test
    fun `a contained command runs unasked until its conversation is tainted, and reaches and writes only inside`() {
        val port = server.address.port
        val outside = Path.of("build/sandbox-test").toAbsolutePath().createDirectories().toRealPath()
        Files.deleteIfExists(outside.resolve("escaped"))
        app.model
            .reply { toolCall("command.exec", exec("curl", "-sS", "-m", "10", "http://127.0.0.1:$port/hello")) }
            .reply { toolCall("command.exec", exec("curl", "-sS", "-f", "-m", "10", "https://example.org/")) }
            .reply {
                toolCall("command.exec", exec("sh", "-c", "echo x > '$outside/escaped' ; echo x > \"\$TMPDIR/ok\""))
            }
            .reply { text("Done.") }

        val asked = mutableListOf<String>()

        app.run(builder(port)) {
            app.send(app.ada, app.adaChat, "Fetch it.")
            repeat(2) {
                val request = app.fake.await(WAIT) {
                    botMessages(app.adaChat.id).firstOrNull { it.buttons.isNotEmpty() && it.text !in asked }
                }
                asked += request.text
                app.fake.press(app.ada, request, "Allow")
            }
            app.message(app.adaChat, "Done.")
            idle()
        }

        val results = app.model.requests.last().history.filterIsInstance<ToolResultEntry>().map { result ->
            result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
        }
        assertEquals(3, results.size, results.toString())
        assertContains(results[0], "Exit code: 0\nhello through the proxy")
        assertContains(results[1], "The sandbox's proxy refused example.org")
        assertContains(results[2], "Operation not permitted")
        assertFalse(Files.exists(outside.resolve("escaped")))
        for (request in asked) {
            assertContains(request, "Why: the chat is in ask mode")
            assertContains(request, "Sandbox: runs in the seatbelt sandbox")
            assertContains(request, "this conversation holds untrusted content")
        }
    }

    private fun exec(vararg argv: String): String =
        JsonObject(mapOf("argv" to JsonArray(argv.map(::JsonPrimitive)))).toString()

    private fun builder(port: Int): PluginHarness.Builder = app.builder(directory)
        .plugin(AlexandriteSandboxSeatbeltIndex())
        .config(
            """
            {
              "previewIntervalMillis": 0,
              "agents": {"coder": {
                "name": "Coder", "model": "scripted/test-model",
                "channels": {"telegram:work": {}},
                "sandbox": {"type": "seatbelt", "network": {"allowedDomains": ["127.0.0.1:$port"]}}
              }}
            }
            """.trimIndent(),
        )
}
