package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOB
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramRestartTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `messages a restart cut off are answered once after it, and answered ones never again`() {
        app.model.reply { hang() }.reply { hang() }
        val builder = app.builder(directory, sqlite = true)

        app.run(builder) {
            app.send(app.ada, app.adaChat, "What does greet do?")
            app.send(app.bob, app.bobChat, "And Main.kt?")
            app.model.awaitRequests(2)
        }
        app.model.reply { text("Answered.") }.reply { text("Answered.") }
        app.run(builder) {
            app.message(app.adaChat, "Answered.")
            app.message(app.bobChat, "Answered.")
            idle()
        }
        app.run(builder) { app.consumed(2) }

        val asked = app.model.requests.map { it.asked }
        assertEquals(setOf("What does greet do?", "And Main.kt?"), asked.take(2).toSet())
        assertEquals(asked.take(2).toSet(), asked.drop(2).toSet())
        assertEquals(4, asked.size)
        assertEquals(listOf("Answered."), app.fake.botMessages(ADA).body().map { it.text })
        assertEquals(listOf("Answered."), app.fake.botMessages(BOB).body().map { it.text })
        app.model.assertFinished()
    }
}
