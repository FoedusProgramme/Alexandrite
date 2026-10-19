package org.unitedsoftware.alexandrite.commands.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.CHINESE
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.PLUGIN
import org.unitedsoftware.alexandrite.commands.TestClock
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.testkit.RecordingAgentControl
import org.unitedsoftware.alexandrite.testkit.RecordingAgentControl.Call
import org.unitedsoftware.alexandrite.testkit.ScriptedModel.Companion.modelInfo
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import java.io.IOException
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingCommandsTest {
    private val clock = TestClock()
    private val control = RecordingAgentControl(clock = clock).apply {
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
    private val commands = SettingCommands(listing, commandTexts(), clock)
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
    fun `model shows the agent's equal models numbered, the one in use marked, and picks one by its number`() =
        runTest {
            val models = listOf("big/m-01", "big/m-02", "big/m-03").map(ModelRef::parse)
            control.scriptAgent(AgentId.MAIN, "Main", models = models)
            val endpoints = "Endpoints: big, slow, broken. List an endpoint's models with /model <endpoint>, switch " +
                "with /model <endpoint>/<model>, go back to the agent's model with /model reset."
            val listed = "The agent's models, the first its default; pick one with /model <number>:\n" +
                "- 1. big/m-01 (in use)\n- 2. big/m-02\n- 3. big/m-03"

            val shown = commands.answer(testCommand("model"))
            assertEquals("Model: big/m-01 (the agent's setting).\n$listed\n$endpoints", shown)
            assertEquals("This chat now uses the model big/m-02.", commands.answer(testCommand("model", "2")))
            assertEquals(
                "There is no model 4. The agent's models:\n- 1. big/m-01\n- 2. big/m-02 (in use)\n- 3. big/m-03",
                commands.answer(testCommand("model", "4")),
            )
            assertEquals(
                listOf(ChatSettingsUpdate.builder().model(models[1]).build()),
                control.calls.filterIsInstance<Call.UpdateSettings>().map { it.update },
            )
        }

    @Test
    fun `a number picks nothing for an agent that names no models`() = runTest {
        assertEquals(
            "The agent names no models to pick by number: switch with /model <endpoint>/<model>.",
            commands.answer(testCommand("model", "1")),
        )
        assertEquals(emptyList(), control.calls.filterIsInstance<Call.UpdateSettings>())
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
        val commands = SettingCommands(bare, commandTexts(), clock)

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
            commands.answer(testCommand("model", "reset/m-01", issuer = OPERATOR)),
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
            SettingCommands(failing, commandTexts(), clock).answer(testCommand("model", "big/m-01"))
        }
    }

    @Test
    fun `a malformed model reference or more than one argument changes nothing`() = runTest {
        val usage = "Send /model to see the model, /model <number> to pick one of the agent's models, " +
            "/model <endpoint> to list an endpoint's models, /model <endpoint>/<model> to switch, or /model reset " +
            "to go back to the agent's."

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
        val commands = SettingCommands(listing, commandTexts(catalog), clock)

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

    // /timezone.

    @Test
    fun `timezone shows, sets and resets the zone, each with the time there`() = runTest {
        control.scriptAgent(AgentId.MAIN, "Main", zone = ZoneId.of("Europe/Berlin"))

        assertEquals(
            "Time zone: Europe/Berlin (the agent's setting). It is 2026-01-01 01:00 +01:00 there now.",
            commands.answer(testCommand("timezone")),
        )
        assertEquals(
            "The time zone of this chat is now Asia/Shanghai. It is 2026-01-01 08:00 +08:00 there now.",
            commands.answer(testCommand("timezone", "asia/shanghai")),
        )
        assertEquals(
            "Time zone: Asia/Shanghai (set in this chat). It is 2026-01-01 08:00 +08:00 there now.",
            commands.answer(testCommand("timezone")),
        )
        commands.answer(testCommand("timezone", "+05:30"))
        assertEquals(
            "The time zone is Europe/Berlin again (the agent's setting). It is 2026-01-01 01:00 +01:00 there now.",
            commands.answer(testCommand("timezone", "reset")),
        )
        assertEquals(
            listOf(
                ChatSettingsUpdate.builder().zone(ZoneId.of("Asia/Shanghai")).build(),
                ChatSettingsUpdate.builder().zone(ZoneOffset.ofHoursMinutes(5, 30)).build(),
                ChatSettingsUpdate.builder().resetZone().build(),
            ),
            control.calls.map { (it as Call.UpdateSettings).update },
        )
    }

    @Test
    fun `timezone falls back to the host's zone, and a zone that is none changes nothing`() = runTest {
        assertEquals(
            "Time zone: UTC (the host's time zone). It is 2026-01-01 00:00 Z there now.",
            commands.answer(testCommand("timezone")),
        )
        assertEquals(
            "'Mars/Olympus' is no time zone. Give a region such as Asia/Shanghai or an offset such as +08:00.",
            commands.answer(testCommand("timezone", "Mars/Olympus")),
        )
        assertEquals(
            "Send /timezone to see the time zone, /timezone <zone> (such as Asia/Shanghai, Europe/Berlin or +08:00) " +
                "to set it, or /timezone reset to go back to the default.",
            commands.answer(testCommand("timezone", "Asia Shanghai")),
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
            Time zone: UTC (the host's time zone)
            Permission mode: ask (the default)
            Home: main has no home chat on test:main
            Linked: no other chat shares the conversation of main
            You are not an operator here.
            """.trimIndent(),
            commands.answer(testCommand("settings")),
        )
    }

    @Test
    fun `settings shows the chat's own settings, the home elsewhere and the linked chats`() = runTest {
        val coder = AgentId("coder")
        control.scriptAgent(coder, "Coder", model = ModelRef.parse("big/m-02"))
        control.updateSettings(testChat("home"), ChatSettingsUpdate.builder().agent(coder).home().build(), OPERATOR)
        control.updateSettings(
            chat,
            ChatSettingsUpdate.builder().agent(coder).reasoning(ReasoningEffort.HIGH).language(CHINESE).build(),
            OPERATOR,
        )
        val bypass = ChatSettingsUpdate.builder().mode(PermissionMode.BYPASS, clock.now + Duration.ofMinutes(90))
        control.updateSettings(chat, bypass.build(), OPERATOR)
        control.link(coder, testChat("other"), chat, OPERATOR)
        clock.now += Duration.ofMinutes(15)

        assertEquals(
            """
            Settings of test:main:chat
            Agent: coder "Coder" (chosen here with /agent)
            Model: big/m-02 (the agent's setting)
            Reasoning effort: high (set in this chat)
            Language: zh-CN (set in this chat)
            Time zone: UTC (the host's time zone)
            Permission mode: bypass (set in this chat) for 1 h 15 min more
            Home: the home chat of coder on test:main is test:main:home
            Linked: shares the conversation of coder with test:main:other (anchor test:main:other)
            You are an operator here.
            """.trimIndent(),
            commands.answer(testCommand("settings", issuer = OPERATOR)),
        )
    }

    @Test
    fun `settings shows a home chat as the source of its agent`() = runTest {
        control.updateSettings(chat, ChatSettingsUpdate.builder().home().build(), OPERATOR)

        val lines = commands.answer(testCommand("settings")).lines()

        assertEquals("Agent: main \"Main\" (this is its home chat)", lines[1])
        assertEquals("Home: this chat is the home chat of main on test:main", lines[7])
    }

    @Test
    fun `settings takes no arguments`() = runTest {
        assertEquals("Send /settings without anything after it.", commands.answer(testCommand("settings", "all")))
    }
}
