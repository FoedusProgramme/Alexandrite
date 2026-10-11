package org.foedusprogramme.alexandrite.commands.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.commands.ADMIN
import org.foedusprogramme.alexandrite.commands.CHINESE
import org.foedusprogramme.alexandrite.commands.PLUGIN
import org.foedusprogramme.alexandrite.commands.answer
import org.foedusprogramme.alexandrite.commands.commandTexts
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl.Call
import org.foedusprogramme.alexandrite.testkit.ScriptedModel.Companion.modelInfo
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testCommand
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingCommandsTest {
    private val control = RecordingAgentControl().apply {
        scriptAgent(AgentId.MAIN, "Main")
        scriptModels(EndpointId("big"), (1..45).map { modelInfo("m-%02d".format(it)) }.shuffled())
        scriptModels(EndpointId("slow"), emptyList())
        scriptModels(EndpointId("broken"), emptyList())
    }

    /** [control], whose listing of `slow` never ends and of `broken` fails. */
    private val listing = object : AgentControl by control {
        override suspend fun models(endpoint: EndpointId): List<ModelInfo> = when (endpoint.value) {
            "slow" -> awaitCancellation()
            "broken" -> throw IOException("connection refused")
            else -> control.models(endpoint)
        }
    }
    private val commands = SettingCommands(listing, commandTexts())
    private val chat = testChat()

    // /model.

    @Test
    fun `model shows the model in force and where it is set`() = runTest {
        val endpoints = "Endpoints: big, slow, broken. List an endpoint's models with /model <endpoint>, switch with " +
            "/model <endpoint>/<model>, go back to the agent's model with /model reset."

        assertEquals(
            "Model: none (neither this chat nor the agent sets one).\n$endpoints",
            commands.answer(testCommand("model")),
        )
        control.scriptAgent(AgentId.MAIN, "Main", model = ModelRef.parse("big/m-01"))
        assertEquals("Model: big/m-01 (the agent's setting).\n$endpoints", commands.answer(testCommand("model")))
        commands.answer(testCommand("model", "big/m-02"))
        assertEquals("Model: big/m-02 (set in this chat).\n$endpoints", commands.answer(testCommand("model")))
    }

    @Test
    fun `model with an endpoint lists its first models sorted, the one in use marked`() = runTest {
        control.scriptAgent(AgentId.MAIN, "Main", model = ModelRef.parse("big/m-03"))

        val reply = commands.answer(testCommand("model", "big"))

        val listed = (1..40).joinToString("\n") { "- m-%02d".format(it) + if (it == 3) " (in use)" else "" }
        assertEquals("The models of big:\n$listed\nAnd 5 more.", reply)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a listing that takes more than 10 seconds or fails is reported`() = runTest {
        assertEquals(
            "The models of slow could not be listed. Try again later.",
            commands.answer(testCommand("model", "slow")),
        )
        assertEquals(10_000, currentTime)
        assertEquals(
            "The models of broken could not be listed. Try again later.",
            commands.answer(testCommand("model", "broken")),
        )
        assertEquals(10_000, currentTime)
    }

    @Test
    fun `an endpoint that lists no models or does not exist is reported`() = runTest {
        val bare = RecordingAgentControl()
        val commands = SettingCommands(bare, commandTexts())

        assertEquals(
            "There is no endpoint 'nowhere'. Endpoints: none.",
            commands.answer(testCommand("model", "nowhere")),
        )
        bare.scriptModels(EndpointId("empty"), emptyList())
        assertEquals("empty lists no models.", commands.answer(testCommand("model", "empty")))
        assertEquals(
            "There is no endpoint 'nowhere'. Endpoints: empty.",
            commands.answer(testCommand("model", "nowhere")),
        )
    }

    @Test
    fun `model with a reference switches the model, and reset goes back to the agent's`() = runTest {
        control.scriptAgent(AgentId.MAIN, "Main", model = ModelRef.parse("big/m-01"))

        assertEquals("This chat now uses the model big/m-02.", commands.answer(testCommand("model", "big/m-02")))
        assertEquals(
            "This chat now uses the model reset/m-01.",
            commands.answer(testCommand("model", "reset/m-01", issuer = ADMIN)),
        )
        assertEquals(
            "This chat uses the model big/m-01 again (the agent's setting).",
            commands.answer(testCommand("model", "reset")),
        )
        assertEquals(
            listOf(
                ChatSettingsUpdate.builder().model(ModelRef.parse("big/m-02")).build(),
                ChatSettingsUpdate.builder().model(ModelRef.parse("reset/m-01")).build(),
                ChatSettingsUpdate.builder().resetModel().build(),
            ),
            control.calls.map { (it as Call.UpdateSettings).update },
        )
    }

    @Test
    fun `a refused model replies with the agent's message`() = runTest {
        control.refuseNext("Model endpoint 'big' has no model 'm-99'.")

        val reply = commands.answer(testCommand("model", "big/m-99"))

        assertEquals("Model endpoint 'big' has no model 'm-99'.", reply)
    }

    @Test
    fun `an error of the control port that is no refusal is no reply`() = runTest {
        val failing = object : AgentControl by control {
            override suspend fun updateSettings(
                chat: ChatAddress,
                update: ChatSettingsUpdate,
                by: ChatUser?,
            ): ChatSettingsSnapshot = throw IllegalArgumentException("A linked group holds each chat once.")
        }

        assertFailsWith<IllegalArgumentException> {
            SettingCommands(failing, commandTexts()).answer(testCommand("model", "big/m-01"))
        }
    }

    @Test
    fun `a malformed model reference or more than one argument changes nothing`() = runTest {
        val usage = "Send /model to see the model, /model <endpoint> to list an endpoint's models, " +
            "/model <endpoint>/<model> to switch, or /model reset to go back to the agent's."

        assertEquals(usage, commands.answer(testCommand("model", "Big/m-01")))
        assertEquals(usage, commands.answer(testCommand("model", "big m-01")))
        assertEquals(emptyList(), control.calls)
    }

    // /reasoning.

    @Test
    fun `reasoning shows, sets and resets the effort`() = runTest {
        val efforts = "Efforts: none, minimal, low, medium, high, xhigh, max."
        control.scriptAgent(AgentId.MAIN, "Main", reasoning = ReasoningEffort.LOW)

        assertEquals(
            "Reasoning effort: low (the agent's setting). $efforts",
            commands.answer(testCommand("reasoning")),
        )
        assertEquals(
            "The reasoning effort of this chat is now high.",
            commands.answer(testCommand("reasoning", "HIGH")),
        )
        assertEquals("Reasoning effort: high (set in this chat). $efforts", commands.answer(testCommand("reasoning")))
        assertEquals(
            "The reasoning effort is low again (the agent's setting).",
            commands.answer(testCommand("reasoning", "reset")),
        )
        control.scriptAgent(AgentId.MAIN, "Main")
        assertEquals(
            "Reasoning effort: default (the model decides). $efforts",
            commands.answer(testCommand("reasoning")),
        )
        assertEquals(
            listOf(
                ChatSettingsUpdate.builder().reasoning(ReasoningEffort.HIGH).build(),
                ChatSettingsUpdate.builder().resetReasoning().build(),
            ),
            control.calls.map { (it as Call.UpdateSettings).update },
        )
    }

    @Test
    fun `an unknown effort or more than one argument changes nothing`() = runTest {
        assertEquals(
            "'turbo' is no reasoning effort. Efforts: none, minimal, low, medium, high, xhigh, max.",
            commands.answer(testCommand("reasoning", "turbo")),
        )
        assertEquals(
            "Send /reasoning to see the effort, /reasoning <effort> to set it, or /reasoning reset to go back to " +
                "the default.",
            commands.answer(testCommand("reasoning", "high now")),
        )
        assertEquals(emptyList(), control.calls)
    }

    // /language.

    @Test
    fun `language replies in the language the chat gets from the change`() = runTest {
        val catalog = TextCatalog.builder()
            .text(PLUGIN, CHINESE, "language.set", "这个聊天的语言现在是 {language}。")
            .build()
        val commands = SettingCommands(listing, commandTexts(catalog))

        assertEquals("Language: default (the host's language).", commands.answer(testCommand("language")))
        assertEquals("这个聊天的语言现在是 zh-CN。", commands.answer(testCommand("language", "zh_cn")))
        assertEquals(
            "The language is default again (the host's language).",
            commands.answer(testCommand("language", "reset")),
        )
        assertEquals(
            listOf(
                ChatSettingsUpdate.builder().language(CHINESE).build(),
                ChatSettingsUpdate.builder().resetLanguage().build(),
            ),
            control.calls.map { (it as Call.UpdateSettings).update },
        )
    }

    @Test
    fun `language shows the agent's language and where it is set`() = runTest {
        control.scriptAgent(AgentId.MAIN, "Main", language = LanguageTag("en-GB"))

        assertEquals("Language: en-GB (the agent's setting).", commands.answer(testCommand("language")))
        commands.answer(testCommand("language", "en-US"))
        assertEquals("Language: en-US (set in this chat).", commands.answer(testCommand("language")))
    }

    @Test
    fun `a malformed language tag changes nothing`() = runTest {
        assertEquals(
            "'!!' is no language tag. Give one such as en or zh-CN.",
            commands.answer(testCommand("language", "!!")),
        )
        assertEquals(emptyList(), control.calls)
    }

    // /settings.

    @Test
    fun `settings shows each setting of a chat that sets nothing as the defaults give it`() = runTest {
        control.scriptAgent(AgentId.MAIN, "Main", model = ModelRef.parse("big/m-01"))

        assertEquals(
            """
            Settings of test:main:chat
            Agent: main "Main" (the default agent of test:main)
            Model: big/m-01 (the agent's setting)
            Reasoning effort: default (the model decides)
            Language: default (the host's language)
            Home: main has no home chat on test:main
            Linked: no other chat shares the conversation of main
            You are not an admin here.
            """.trimIndent(),
            commands.answer(testCommand("settings")),
        )
    }

    @Test
    fun `settings shows the chat's own settings, the home elsewhere and the linked chats`() = runTest {
        val coder = AgentId("coder")
        control.scriptAgent(coder, "Coder", model = ModelRef.parse("big/m-02"))
        control.updateSettings(testChat("home"), ChatSettingsUpdate.builder().agent(coder).home().build(), ADMIN)
        control.updateSettings(
            chat,
            ChatSettingsUpdate.builder().agent(coder).reasoning(ReasoningEffort.HIGH).language(CHINESE).build(),
            ADMIN,
        )
        control.link(coder, testChat("other"), chat, ADMIN)

        assertEquals(
            """
            Settings of test:main:chat
            Agent: coder "Coder" (chosen here with /agent)
            Model: big/m-02 (the agent's setting)
            Reasoning effort: high (set in this chat)
            Language: zh-CN (set in this chat)
            Home: the home chat of coder on test:main is test:main:home
            Linked: shares the conversation of coder with test:main:other (anchor test:main:other)
            You are an admin here.
            """.trimIndent(),
            commands.answer(testCommand("settings", issuer = ADMIN)),
        )
    }

    @Test
    fun `settings shows a home chat as the source of its agent`() = runTest {
        control.updateSettings(chat, ChatSettingsUpdate.builder().home().build(), ADMIN)

        val lines = commands.answer(testCommand("settings")).lines()

        assertEquals("Agent: main \"Main\" (this is its home chat)", lines[1])
        assertEquals("Home: this chat is the home chat of main on test:main", lines[5])
    }

    @Test
    fun `settings takes no arguments`() = runTest {
        assertEquals("Send /settings without anything after it.", commands.answer(testCommand("settings", "all")))
    }
}
