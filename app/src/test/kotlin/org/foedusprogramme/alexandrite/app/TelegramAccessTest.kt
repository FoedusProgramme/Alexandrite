package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.ADA
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.BOB
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.CODE
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.GROUP
import org.foedusprogramme.alexandrite.app.control.ControlAnswers
import org.foedusprogramme.alexandrite.app.control.controlSocket
import org.foedusprogramme.alexandrite.app.control.serveControl
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDesk
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TelegramAccessTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    /** What `alexandrite <args>` printed to stdout and returned against the app of [directory]. */
    private fun cli(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = run(
            args.toList() + listOf("--data-dir", "${app.dataDir(directory)}"),
            emptyMap(),
            "Linux",
            directory.resolve("home"),
            PrintStream(out, true),
            PrintStream(ByteArrayOutputStream(), true),
        )
        return code to out.toString(Charsets.UTF_8)
    }

    @Test
    fun `a chat that is not paired gets a code and no answer until the operator pairs it from the command line`() {
        app.model.reply { text("Hello, Bob.") }
        var paired = 0 to ""
        var allowed: JsonArray? = null

        app.run(app.builder(directory, paired = listOf(ADA))) {
            app.send(app.bob, app.bobChat, "Hello?")
            val offer = app.messages(app.bobChat, 1).single().text
            val code = checkNotNull(CODE.find(offer)) { offer }.value
            assertEquals(emptyList(), app.model.requests)
            coroutineScope {
                val answers = ControlAnswers("test") { getAll<PairingDesk>() }
                val control = launch(Dispatchers.IO) { serveControl(app.dataDir(directory), answers::answer) }
                withContext(Dispatchers.IO) {
                    awaitControlSocket(controlSocket(app.dataDir(directory)))
                    paired = cli("pair", code)
                }
                control.cancel()
            }
            app.send(app.bob, app.bobChat, "Hello again?")
            app.message(app.bobChat, "Hello, Bob.")
            idle()
            allowed = readConfig().tree("channels.telegram.instances.work")?.get("allowedChats") as? JsonArray
        }

        assertContains(
            app.fake.botMessages(BOB).first().text,
            "This chat is not paired with this bot yet. To pair it, give the bot's operator the code ",
        )
        assertEquals(0 to "Paired telegram:work:$BOB (direct, from Bob @bob_test, telegram:work@$BOB).\n", paired)
        assertEquals("Hello again?", app.model.requests.single().asked)
        assertEquals(2, app.fake.botMessages(BOB).size)
        assertEquals(JsonArray(listOf(JsonPrimitive(ADA), JsonPrimitive(BOB))), allowed)
    }

    @Test
    fun `a paired group is answered only when it mentions the bot, and a member's admin-only command stays there`() {
        app.model.reply { text("It says hello.") }
        var before: ConversationId? = null
        var after: ConversationId? = null

        app.run(app.builder(directory, paired = listOf(ADA, GROUP))) {
            app.send(app.bob, app.group, "Lunch at noon?")
            app.consumed(1)
            val asked = app.send(app.bob, app.group, "$BOT_MENTION what does greet do?")
            val answer = app.message(app.group, "It says hello.")
            idle()
            before = get<ConversationStore>().current(app.key(app.group)).id
            val refused = app.send(app.bob, app.group, "/new")
            val refusal = app.message(app.group, "Only an admin of this bot may use /new in this chat.")
            idle()
            after = get<ConversationStore>().current(app.key(app.group)).id
            assertEquals(asked to refused, answer.replyTo to refusal.replyTo)
        }

        val request = app.model.requests.single()
        val entry = request.history.filterIsInstance<UserEntry>().single()
        assertEquals(listOf("what does greet do?"), entry.parts.filterIsInstance<TextPart>().map { it.text })
        assertFalse(entry.parts.filterIsInstance<ContextPart>().any { BOT_MENTION in it.text })
        assertContains(entry.parts.filterIsInstance<ContextPart>().single().text, "Chat: group \"Backend\"")
        assertEquals(2, app.fake.botMessages(GROUP).size)
        assertEquals(before, after)
        assertEquals(request.ids.conversation, after)
    }
}
