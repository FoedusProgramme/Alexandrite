package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TelegramRepositoryTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    private val repository: Path get() = directory.toRealPath().resolve("repo")
    private val readme: Path get() = repository.resolve("README.md")
    private val configFile: Path get() = directory.toRealPath().resolve("config/alexandrite.json")
    private val greeter: Path get() = repository.resolve("src/main/kotlin/Greeter.kt")

    @AfterTest
    fun close() {
        app.close()
    }

    /** The agent's config with [folder] trusted. */
    private fun trusting(folder: Path): String = """
        {
          "previewIntervalMillis": 0,
          "trustedFolders": [${JsonPrimitive("$folder")}],
          "agents": {
            "coder": {
              "name": "Coder",
              "model": "scripted/test-model",
              "reasoning": "low",
              "channels": {"telegram:work": {}}
            }
          }
        }
    """.trimIndent()

    private fun write(file: Path, text: String) {
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun arguments(vararg values: Pair<String, Any>): String =
        JsonObject(values.associate { (name, value) -> name to JsonPrimitive("$value") }).toString()

    private val ToolResultEntry.text: String get() = content.joinToString("") { (it as TextPart).text }

    /** What [entry] holds, one line per part. */
    private fun described(entry: TranscriptEntry): String = when (entry) {
        is UserEntry -> "user: ${entry.words}"

        is AssistantEntry -> entry.parts.joinToString("\n") { part ->
            when (part) {
                is TextPart -> "assistant: ${part.text}"
                is ToolCallPart -> "call: ${part.name}"
                else -> "assistant part: $part"
            }
        }

        is ToolResultEntry -> "result of ${entry.toolName} (${entry.outcome}): ${entry.text}"

        is NoticeEntry -> "notice ${entry.kind}: ${entry.text}"

        else -> "$entry"
    }

    @Test
    fun `an admin's direct chat asks about a repository, sees the answer stream in, and finds the turn in SQLite`() {
        write(readme, "# Greeter\n")
        write(greeter, GREETER)
        val previewed = CompletableDeferred<Unit>()
        app.model
            .reply {
                text("Let me look around the repository.")
                toolCall("fs.glob", arguments("pattern" to "**/*.kt", "path" to repository))
            }
            .reply { toolCall("fs.grep", arguments("pattern" to "fun greet", "path" to repository)) }
            .reply { toolCall("fs.read", arguments("path" to greeter)) }
            .reply {
                text("`greet(name)` in `Greeter.kt` says hello.")
                pause { previewed.await() }
                text("It returns the greeting.")
            }
        val builder = app.builder(directory, sqlite = true).config(trusting(repository))

        app.run(builder) {
            app.send(app.ada, app.adaChat, "What does greet do?")
            app.messages(app.adaChat, 2)
            previewed.complete(Unit)
            app.message(app.adaChat, ANSWER)
            idle()
        }
        var conversation: ConversationId? = null
        var entries = emptyList<TranscriptEntry>()
        var end: TurnEndKind? = null
        app.run(builder) {
            val conversations = get<ConversationStore>()
            val current = conversations.current(app.key(app.adaChat)).id
            conversation = current
            entries = get<TranscriptStore>().entries(current)
            end = conversations.turn(app.model.requests.first().ids.turn)?.end
        }

        val (frozen, answer) = app.fake.botMessages(ADA).body()
        assertEquals(2, app.fake.botMessages(ADA).body().size)
        assertEquals("Let me look around the repository." to 0, frozen.text to frozen.edits)
        assertEquals(ANSWER, answer.text)
        assertEquals(
            """[{"type":"code","offset":0,"length":11},{"type":"code","offset":15,"length":10}]""",
            answer.entities.toString(),
        )
        val typing = app.fake.calls("sendChatAction").first().params
        assertEquals(listOf(JsonPrimitive(ADA), JsonPrimitive("typing")), listOf(typing["chat_id"], typing["action"]))
        val requests = app.model.requests
        assertEquals(listOf(0, 1, 2, 3), requests.map { it.ids.round })
        assertEquals("What does greet do?", requests.first().asked)
        assertEquals(
            listOf(
                "agent.delegate",
                "agent.jobs",
                "agent.send",
                "agent.stop",
                "chat.list",
                "chat.send",
                "chat.send_file",
                "command.exec",
                "config.get",
                "config.set",
                "config.unset",
                "fs.edit",
                "fs.glob",
                "fs.grep",
                "fs.read",
                "fs.write",
                "history.search",
                "permission.status",
                "profile.create",
                "profile.edit",
                "profile.list",
                "profile.remove",
                "skill.load",
                "telegram.member",
                "telegram.pin",
                "telegram.poll",
                "telegram.react",
                "telegram.set_title",
                "telegram.sticker",
                "web.fetch",
                "web.search",
            ),
            requests.first().tools.map { it.name },
        )
        assertEquals(requests.first().ids.conversation, conversation)
        assertEquals(TurnEndKind.COMPLETED, end)
        val grepped = "$greeter\n  3: fun greet(name: String) = \"Hello, \$name!\"\n"
        val read = "1\tpackage demo\n2\t\n3\tfun greet(name: String) = \"Hello, \$name!\"\n"
        assertEquals(
            listOf(
                "user: What does greet do?",
                "assistant: Let me look around the repository.\ncall: fs.glob",
                "result of fs.glob (${ToolOutcome.Succeeded}): $greeter",
                "call: fs.grep",
                "result of fs.grep (${ToolOutcome.Succeeded}): $grepped",
                "call: fs.read",
                "result of fs.read (${ToolOutcome.Succeeded}): $read",
                "assistant: `greet(name)` in `Greeter.kt` says hello.\nassistant: It returns the greeting.",
            ),
            entries.map(::described),
        )
    }

    @Test
    fun `an answer too long for ten messages fills ten and goes out whole as a file`() {
        val listing = (1..2_000).joinToString("\n") { "Line $it of the listing." }
        val previewed = CompletableDeferred<Unit>()
        app.model.reply {
            text(listing)
            pause { previewed.await() }
        }

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "Print the whole listing.")
            app.messages(app.adaChat, 1)
            previewed.complete(Unit)
            app.fake.await(30.seconds) { botMessages(ADA).takeIf { messages -> messages.any { it.file != null } } }
            idle()
        }

        val messages = app.fake.botMessages(ADA)
        assertEquals(11, messages.size)
        assertEquals(listOf("Line 1 of the listing."), messages.first().text.lines().take(1))
        val file = checkNotNull(messages.last().file)
        assertEquals("answer.md" to listing, file.name to file.bytes.decodeToString())
        assertEquals("The answer goes on beyond these messages: this file holds all of it.", messages.last().text)
        val conversation = app.model.requests.single().ids.conversation
        val entries = runBlocking { app.store.transcripts.entries(conversation) }
        assertEquals(
            listOf("user: Print the whole listing.", "assistant: $listing"),
            entries.map(::described),
        )
    }

    @Test
    fun `an admin trusts the folder the agent warned of, and its next file read there loads the project's AGENTS md`() {
        write(readme, "# Greeter\n")
        write(repository.resolve("AGENTS.md"), "Answer in one sentence.\n")
        repository.resolve(".git").createDirectories()
        app.model
            .reply { toolCall("fs.read", arguments("path" to readme)) }
            .reply { text("The project is called Greeter.") }
            .reply { toolCall("fs.read", arguments("path" to readme)) }
            .reply { text("Greeter greets.") }
        lateinit var config: String
        var listed = ""

        app.run(app.builder(directory, sqlite = true)) {
            config = Files.readString(configFile)
            app.send(app.ada, app.adaChat, "What is this project?")
            val asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
            app.fake.press(app.ada, asked, "Allow, mark tainted")
            app.message(app.adaChat, "The project is called Greeter.")
            app.send(app.ada, app.adaChat, "/trust $repository")
            app.message(app.adaChat, "$repository is trusted, and so are the folders below it.")
            app.send(app.ada, app.adaChat, "What does it do?")
            app.message(app.adaChat, "Greeter greets.")
            idle()
        }
        app.run(app.builder(directory, sqlite = true)) {
            app.send(app.ada, app.adaChat, "/trust")
            listed = app.bodyMessages(app.adaChat, 6).last().text
        }

        assertEquals(
            listOf(
                "Coder is working in $repository, which has project instructions (AGENTS.md) that were not loaded. " +
                    "An operator can trust the folder with /trust $repository; they load the next time the agent " +
                    "touches it.",
                "The project is called Greeter.",
                "$repository is trusted, and so are the folders below it.",
                "Greeter greets.",
            ),
            app.fake.botMessages(ADA).body().map { it.text }.filterNot { it.startsWith("Approval needed") }.take(4),
        )
        val today = LocalDate.now(ZoneOffset.UTC)
        assertContains(
            setOf(today, today.minusDays(1)).map {
                "Trusted folders, each with the folders below it:\n- $repository (trusted by Ada Lovelace on $it)"
            },
            listed.substringBefore("\n\n"),
        )
        assertEquals(config, Files.readString(configFile))
        val requests = app.model.requests
        assertEquals(4, requests.size)
        assertEquals(emptyList(), requests[1].history.filter { it.isProject() })
        val loaded = requests[3].history.filterIsInstance<UserEntry>().single { it.isProject() }
        assertEquals("Answer in one sentence.\n", loaded.words)
        assertContains(
            loaded.parts.filterIsInstance<ContextPart>().single().text,
            "${repository.resolve("AGENTS.md")} (folder trusted by Ada Lovelace)",
        )
        assertEquals(loaded, requests[3].history[requests[3].history.indexOfLast { it is ToolResultEntry } + 1])
    }

    private fun TranscriptEntry.isProject(): Boolean =
        this is UserEntry && (origin as? UserOrigin.SystemWritten)?.source == "alexandrite.project"

    private companion object {
        const val GREETER = "package demo\n\nfun greet(name: String) = \"Hello, \$name!\"\n"
        const val ANSWER = "greet(name) in Greeter.kt says hello.\n\nIt returns the greeting."
    }
}
