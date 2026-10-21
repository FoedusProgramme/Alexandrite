package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.CODE
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.FAST_MODEL
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.GROUP
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotMessage
import org.unitedsoftware.alexandrite.sdk.hook.ObserverHook
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.TurnCommitted
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TelegramChatTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `chat send answers an earlier message of a direct chat as a reply to it`() {
        app.model.reply { text("Noted.") }
        lateinit var answer: FakeBotMessage
        var first = 0L

        app.run(app.builder(directory)) {
            first = app.send(app.ada, app.adaChat, "First question.")
            app.message(app.adaChat, "Noted.")
            idle()
            app.model
                .reply { toolCall("chat.send", """{"to": "here", "replyTo": "$first", "text": "About that one."}""") }
                .reply { text("") }
            app.send(app.ada, app.adaChat, "Second question.")
            answer = app.message(app.adaChat, "About that one.")
            idle()
        }

        assertEquals(first, answer.replyTo)
        assertEquals(
            listOf("Noted.", "Sending 1 message\nhere", "About that one."),
            app.fake.botMessages(ADA).map {
                it.text
            },
        )
    }

    @Test
    fun `cancel ends the answer that is running, keeps its preview and lets the next message run`() {
        app.model
            .reply {
                text("Reading every file of the repository first.")
                hang()
            }
            .reply { text("Still here.") }

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "Summarize the whole repository.")
            app.messages(app.adaChat, 1)
            app.send(app.ada, app.adaChat, "/cancel")
            app.message(app.adaChat, CANCELLED)
            idle()
            app.send(app.ada, app.adaChat, "Are you there?")
            app.message(app.adaChat, "Still here.")
            idle()
        }

        assertEquals(
            listOf(
                "Reading every file of the repository first." to 0,
                CANCELLED to 0,
                "Still here." to 0,
            ),
            app.fake.botMessages(ADA).map { it.text to it.edits },
        )
        val (cancelled, next) = app.model.requests
        val ended = runBlocking { app.store.conversations.turn(cancelled.ids.turn)?.end }
        assertEquals(TurnEndKind.CANCELLED, ended)
        assertEquals(listOf("", "Are you there?"), next.history.filterIsInstance<UserEntry>().map { it.words })
    }

    @Test
    fun `cancel after the answer arrived, while its turn still wraps up, says that no answer is running`() {
        app.model.reply { text("All done.") }
        val committing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val wrapUp = object : ObserverHook<TurnCommitted> {
            override val point: ObserverPoint<TurnCommitted> = TurnPoints.TURN_COMMITTED

            override suspend fun observe(payload: TurnCommitted) {
                committing.complete(Unit)
                release.await()
            }
        }

        app.run(app.builder(directory).hook(wrapUp)) {
            app.send(app.ada, app.adaChat, "Finish the change.")
            app.message(app.adaChat, "All done.")
            committing.await()
            app.send(app.ada, app.adaChat, "/cancel")
            app.message(app.adaChat, NOTHING_RUNNING)
            release.complete(Unit)
            idle()
        }

        assertEquals(listOf("All done.", NOTHING_RUNNING), app.fake.botMessages(ADA).map { it.text })
        val turn = app.model.requests.single().ids.turn
        assertEquals(TurnEndKind.COMPLETED, runBlocking { app.store.conversations.turn(turn)?.end })
    }

    @Test
    fun `new starts another conversation for the next message`() {
        app.model
            .reply { text("Hello, Ada.") }
            .reply { text("Hello again.") }

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "Hello!")
            app.message(app.adaChat, "Hello, Ada.")
            app.send(app.ada, app.adaChat, "/new")
            app.message(app.adaChat, "A new conversation with coder has started.")
            app.send(app.ada, app.adaChat, "Do you remember me?")
            app.message(app.adaChat, "Hello again.")
            idle()
        }

        val (first, second) = app.model.requests
        assertNotEquals(first.ids.conversation, second.ids.conversation)
        assertEquals(listOf("Do you remember me?"), second.history.filterIsInstance<UserEntry>().map { it.words })
        val current = runBlocking { app.store.conversations.current(app.key(app.adaChat)).id }
        assertEquals(second.ids.conversation, current)
    }

    @Test
    fun `the model chosen in a chat answers its next message`() {
        app.model.reply { text("I run on the fast model.") }

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "/model scripted/$FAST_MODEL")
            app.message(app.adaChat, "This chat now uses the model scripted/$FAST_MODEL.")
            app.send(app.ada, app.adaChat, "Which model are you?")
            app.message(app.adaChat, "I run on the fast model.")
            idle()
        }

        assertEquals(FAST_MODEL, app.model.requests.single().model.model)
    }

    @Test
    fun `link shares a direct chat's conversation with a group, and each chat gets its own answers`() {
        app.model
            .reply { text("Noted: the release is on Friday.") }
            .reply { text("On Friday.") }
            .reply { text("Still on Friday.") }
        val configFile = directory.toRealPath().resolve("config/alexandrite.json")
        lateinit var config: String

        app.run(app.builder(directory, paired = listOf(ADA, GROUP), sqlite = true)) {
            config = Files.readString(configFile)
            app.send(app.ada, app.adaChat, "/link")
            val offer = app.messages(app.adaChat, 1).single().text
            val code = checkNotNull(CODE.find(offer)) { offer }.value
            app.send(app.ada, app.group, "/link $code")
            app.message(app.group, "This chat now shares the conversation of coder with telegram:work:$ADA.")
            app.message(app.adaChat, "telegram:work:$GROUP now shares the conversation of coder with this chat.")
            // The group's command records itself for the model after it replies.
            idle()
            app.send(app.ada, app.adaChat, "Remember: the release is on Friday.")
            app.message(app.adaChat, "Noted: the release is on Friday.")
            val asked = app.send(app.bob, app.group, "$BOT_MENTION when is the release?")
            app.message(app.group, "On Friday.")
            idle()
            assertEquals(asked, app.fake.botMessages(GROUP).last().replyTo)
        }
        app.run(app.builder(directory, paired = listOf(ADA, GROUP), sqlite = true)) {
            app.send(app.bob, app.group, "$BOT_MENTION and after the restart?")
            app.message(app.group, "Still on Friday.")
            idle()
            val group = get<ChatLinks>().groups().single()
            assertEquals(
                listOf(app.address(app.adaChat), app.address(app.group)),
                listOf(group.anchor) + group.members,
            )
            assertEquals(setOf(app.address(app.group)), group.links.keys)
        }

        assertContains(
            app.fake.botMessages(ADA).first().text,
            "To share the conversation of coder in this chat with another chat, an operator sends /link ",
        )
        val (direct, group, restarted) = app.model.requests
        assertEquals(
            setOf(direct.ids.conversation),
            listOf(direct, group, restarted).map {
                it.ids.conversation
            }.toSet(),
        )
        assertEquals(
            listOf("", "Remember: the release is on Friday.", "when is the release?"),
            group.history.filterIsInstance<UserEntry>().map { it.words },
        )
        assertEquals(
            listOf("Noted: the release is on Friday.", "Still on Friday."),
            listOf(
                app.fake.botMessages(ADA).last { !it.text.startsWith(TelegramApp.OFFERED) }.text,
                app.fake.botMessages(GROUP).last().text,
            ),
        )
        assertEquals(config, Files.readString(configFile))
    }
}

private const val CANCELLED = "The answer that was running in this chat is stopped. Messages that were waiting are " +
    "answered next, and background jobs go on (stop one with /jobs stop <id>)."

private const val NOTHING_RUNNING = "No answer is running in this chat."
