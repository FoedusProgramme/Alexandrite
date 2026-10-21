package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.CODER
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The running app over a fake Bot API: a person's message counts as activity, and a newer release reaches the home. */
class TelegramUpdateTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `the default agent tells its home chat of a newer release once`() {
        app.model.reply { text("Hello, Ada.") }
            .reply { toolCall("chat.send", """{"to": "home", "text": "Alexandrite 999.0.0 is out."}""") }
            .reply { text("") }
        val builder = app.builder(directory).config(AGENT_CONFIG).plugin(AlexandriteAppIndex())

        app.run(builder) {
            val desk = get<UpdateDesk>()
            assertNull(desk.lastMessage)
            app.send(app.ada, app.adaChat, "Hi")
            app.message(app.adaChat, "Hello, Ada.")
            idle()
            assertNotNull(desk.lastMessage)
            assertEquals(CODER, desk.defaultAgent())
            assertTrue(!desk.busy())
            val watch = UpdateWatch(
                "1.0.0",
                UpdatesConfig(),
                { "999.0.0" },
                "https://releases.test",
                desk,
                desk.initiator,
                desk.records,
                null,
                Clock.systemUTC(),
            )
            watch.tick()
            app.message(app.adaChat, "Alexandrite 999.0.0 is out.")
            withTimeout(WAIT) { app.model.awaitRequests(3) }
            idle()
            watch.tick()
            idle()
        }

        assertEquals(3, app.model.requests.size)
        val prompt = app.model.requests[1].history.filterIsInstance<UserEntry>().last().parts
            .joinToString("\n") { (it as? TextPart)?.text ?: (it as? ContextPart)?.text.orEmpty() }
        assertTrue(prompt.contains("Alexandrite 999.0.0 is available; this host runs 1.0.0."), prompt)
    }

    private companion object {
        val AGENT_CONFIG = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "name": "Coder",
                  "model": "scripted/test-model",
                  "channels": {"telegram:work": {"home": "$ADA"}}
                }
              }
            }
        """.trimIndent()
    }
}
