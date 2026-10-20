package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.CODE
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.CODER
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.FAST_MODEL
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.GROUP
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
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
            app.message(app.adaChat, "The answers and the jobs that were running in this chat are stopped.")
            idle()
            app.send(app.ada, app.adaChat, "Are you there?")
            app.message(app.adaChat, "Still here.")
            idle()
        }

        assertEquals(
            listOf(
                "Reading every file of the repository first." to 0,
                "The answers and the jobs that were running in this chat are stopped." to 0,
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
    fun `model and settings show each value in force with its source, and the next message uses the chosen model`() {
        app.model.reply { text("I run on the fast model.") }

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "/model")
            app.messages(app.adaChat, 1)
            app.send(app.ada, app.adaChat, "/model scripted/$FAST_MODEL")
            app.messages(app.adaChat, 2)
            app.send(app.ada, app.adaChat, "/settings")
            app.messages(app.adaChat, 3)
            app.send(app.ada, app.adaChat, "Which model are you?")
            app.message(app.adaChat, "I run on the fast model.")
            idle()
        }

        val (model, chosen, settings) = app.fake.botMessages(ADA).map { it.text }
        assertEquals(
            "Model: scripted/test-model (the agent's setting).\nEndpoints: scripted. List an endpoint's models with " +
                "/model <endpoint>, switch with /model <endpoint>/<model>, go back to the agent's model with " +
                "/model reset.",
            model,
        )
        assertEquals("This chat now uses the model scripted/$FAST_MODEL.", chosen)
        assertEquals(
            """
            Settings of telegram:work:$ADA
            Agent: coder "Coder" (the default agent of telegram:work)
            Model: scripted/$FAST_MODEL (set in this chat)
            Reasoning effort: low (the agent's setting)
            Language: default (the host's language)
            Time zone: UTC (the host's time zone)
            Permission mode: ask (the default)
            Conversation: not tainted.
            Home: coder has no home chat on telegram:work
            Linked: no other chat shares the conversation of coder
            You are an operator here.
            """.trimIndent(),
            settings,
        )
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
