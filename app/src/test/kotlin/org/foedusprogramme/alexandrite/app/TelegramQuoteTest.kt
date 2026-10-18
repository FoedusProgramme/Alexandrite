package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.sdk.chat.QuoteTrust
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TelegramQuoteTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    /** Where the message that opened the request's turn came from. */
    private val ModelRequest.opener: UserOrigin.FromChat
        get() = history.filterIsInstance<UserEntry>().last().origin as UserOrigin.FromChat

    /** How far the channel trusted the quote of the message that opened the request's turn. */
    private val ModelRequest.quoteTrust: QuoteTrust? get() = opener.quote?.trust

    @Test
    fun `a quote of the bot's answer to an admin is vouched for after a restart, of its answer to a member not`() {
        repeat(4) { app.model.reply { text("Answered.") } }
        val builder = app.builder(directory, sqlite = true)
        app.run(builder) {
            app.send(app.ada, app.adaChat, "What does greet do?")
            app.message(app.adaChat, "Answered.")
            app.send(app.bob, app.bobChat, "And Main.kt?")
            app.message(app.bobChat, "Answered.")
            idle()
        }
        val toAda = app.fake.botMessages(TelegramApp.ADA).body().single().id
        val toBob = app.fake.botMessages(TelegramApp.BOB).body().single().id

        app.run(builder) {
            app.fake.send(app.ada, app.adaChat, "Say more.", replyTo = toAda)
            app.fake.send(app.bob, app.bobChat, "Say more.", replyTo = toBob)
            app.model.awaitRequests(4)
            idle()
            val conversations = get<ConversationStore>()
            assertNull(conversations.taint(conversations.current(app.key(app.adaChat)).id))
            assertNotNull(conversations.taint(conversations.current(app.key(app.bobChat)).id))
        }

        val trust = app.model.requests.drop(2).associate { it.opener.sender.displayName to it.quoteTrust }
        assertEquals(
            mapOf(
                "Ada Lovelace" to QuoteTrust(live = true, content = true),
                "Bob" to QuoteTrust(live = true, content = false),
            ),
            trust,
        )
    }
}
