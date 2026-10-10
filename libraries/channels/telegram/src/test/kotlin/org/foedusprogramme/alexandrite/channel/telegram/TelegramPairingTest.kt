package org.foedusprogramme.alexandrite.channel.telegram

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDecision
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDesk
import org.foedusprogramme.alexandrite.channel.common.pairing.Unpairing
import org.foedusprogramme.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.foedusprogramme.alexandrite.channel.telegram.TelegramHarness.Companion.BOB
import org.foedusprogramme.alexandrite.channel.telegram.TelegramHarness.Companion.GROUP
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeUser
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.ConfigFile
import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordingTurnSubmitter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class TelegramPairingTest {
    @TempDir
    lateinit var directory: Path

    private val telegram = TelegramHarness()
    private val fake = telegram.fake
    private val file: Path get() = directory.resolve("alexandrite.json")

    @AfterTest
    fun close() {
        telegram.close()
    }

    private fun PluginHarness.Running.desk(): PairingDesk = getAll<PairingDesk>().single()

    private fun PluginHarness.Running.allowedChats(): JsonArray? =
        readConfig().tree("channels.telegram.instances.work")?.get("allowedChats") as? JsonArray

    private fun code(text: String): String = checkNotNull(CODE.find(text)) { "No code in: $text" }.value

    @Test
    fun `a direct chat that is not paired gets its code once, and is paired at once when the code is approved`() {
        val lines = runBlocking {
            logged {
                telegram.run(telegram.builder("work" to telegram.instance(allowed = "[]")).writeConfigFile(file)) {
                    fake.send(telegram.bob, telegram.bobChat, "Hello?")
                    val offer = fake.await { botMessages(BOB).singleOrNull() }
                    fake.send(telegram.bob, telegram.bobChat, "Anyone?")
                    telegram.consumed(2)

                    assertEquals(listOf(offer), fake.botMessages(BOB))
                    assertEquals(emptyList(), telegram.submitter.submissions)
                    assertEquals(null, offer.replyTo)
                    val request = desk().pending().single()
                    assertEquals(code(offer.text), request.code)
                    assertEquals(telegram.address(BOB), request.chat)
                    assertEquals(ChatKind.DIRECT, request.kind)
                    assertEquals("Bob", request.requester?.displayName)

                    assertIs<PairingDecision.Approved>(desk().approve(request.code))

                    fake.send(telegram.bob, telegram.bobChat, "Now?")
                    val message = assertIs<Submission.Message>(telegram.submitted(1).single()).message
                    assertEquals("Now?", message.text)
                    assertEquals(JsonArray(listOf(JsonPrimitive(BOB))), allowedChats())
                    assertEquals(listOf(telegram.address(BOB)), desk().paired())
                }
            }
        }

        assertContains(
            fake.botMessages(BOB).first().text,
            "This chat is not paired with this bot yet. To pair it, give the bot's operator the code ",
        )
        val drops = lines.filter { "is not paired" in it }
        assertEquals(1, drops.size, lines.joinToString("\n"))
        assertContains(drops.single(), "Dropped a message of chat telegram:work:$BOB (direct) from user $BOB @bob_test")
        assertContains(drops.single(), "alexandrite pair <code>")
    }

    @Test
    fun `a group that is not paired gets a code only when it addresses the bot, and a denied code pairs nothing`() {
        telegram.run(telegram.builder("work" to telegram.instance(allowed = "[$ADA]")).writeConfigFile(file)) {
            fake.send(telegram.bob, telegram.group, "Lunch at noon?")
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot are you there?")
            val offer = fake.await { botMessages(GROUP).singleOrNull() }
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot hello?")
            telegram.consumed(3)

            assertEquals(2L, offer.replyTo)
            assertEquals(listOf(offer), fake.botMessages(GROUP))
            val request = desk().pending().single()
            assertEquals(ChatKind.GROUP to "Backend", request.kind to request.title)
            assertIs<PairingDecision.Denied>(desk().deny(code(offer.text)))
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot please?")
            telegram.consumed(4)

            assertEquals(listOf(offer), fake.botMessages(GROUP))
            assertEquals(emptyList(), desk().pending())
            assertEquals(emptyList(), telegram.submitter.submissions)
            assertEquals(JsonArray(listOf(JsonPrimitive(ADA))), allowedChats())
        }
    }

    @Test
    fun `an unpaired chat no longer reaches the agent and leaves the config file`() {
        telegram.run(telegram.builder().writeConfigFile(file)) {
            fake.send(telegram.bob, telegram.bobChat, "one")
            telegram.submitted(1)

            assertEquals(Unpairing.Unpaired, desk().unpair(telegram.address(BOB)))
            fake.send(telegram.bob, telegram.bobChat, "two")
            fake.await { botMessages(BOB).singleOrNull() }
            telegram.consumed(2)

            assertEquals(1, telegram.submitter.submissions.size)
            assertEquals(JsonArray(listOf(JsonPrimitive(ADA), JsonPrimitive(GROUP))), allowedChats())
            assertEquals(Unpairing.NotPaired, desk().unpair(telegram.address(BOB)))
        }
    }

    @Test
    fun `an approval keeps the entries of the file that name environment variables`() {
        val carol = FakeUser(1003, "Carol")
        val carolChat = fake.direct(carol)
        val submitter = RecordingTurnSubmitter(TurnOutcome.Completed(null))
        val environment = mapOf("TELEGRAM_TOKEN" to fake.token, "BOB_CHAT" to "$BOB")
        Files.writeString(
            file,
            """{"channels": {"telegram": {"instances": {"work": {"botToken": "${'$'}{env:TELEGRAM_TOKEN}", """ +
                """"apiBaseUrl": "${fake.baseUrl}", "adminUserIds": [$ADA], """ +
                """"allowedChats": ["${'$'}{env:BOB_CHAT}", $ADA], "polling": {"timeoutSeconds": 1}}}}}}""",
        )
        val config = RuntimeConfig.builder(directory.resolve("data")).configFile(file).environment(environment).build()
        val plugins = PluginSet.of(AlexandriteChannelTelegramIndex()) + SubmitterIndex(submitter)
        val spec = RuntimeSpec.builder(config, plugins).pluginConfig(ConfigFile.read(file, environment)).build()

        runBlocking {
            withTimeout(60.seconds) {
                AlexandriteRuntime.run(spec) {
                    val desk = services.resolver().getAll(key<PairingDesk>()).single()
                    fake.send(telegram.bob, telegram.bobChat, "Paired through the environment")
                    withTimeout(10.seconds) { submitter.awaitSubmissions(1) }
                    fake.send(carol, carolChat, "Me too?")
                    val code = code(fake.await { botMessages(carolChat.id).singleOrNull() }.text)

                    assertIs<PairingDecision.Approved>(desk.approve(code))
                    assertEquals(Unpairing.ByReference, desk.unpair(telegram.address(BOB)))
                    fake.send(carol, carolChat, "Now?")
                    fake.send(telegram.bob, telegram.bobChat, "Still here")
                    withTimeout(10.seconds) { submitter.awaitSubmissions(3) }
                }
            }
        }

        val work = Json.parseToJsonElement(Files.readString(file)).jsonObject["channels"]?.jsonObject
            ?.get("telegram")?.jsonObject?.get("instances")?.jsonObject?.get("work")?.jsonObject
        assertEquals(
            JsonArray(listOf(JsonPrimitive("${'$'}{env:BOB_CHAT}"), JsonPrimitive(ADA), JsonPrimitive(1003))),
            work?.get("allowedChats"),
        )
        assertEquals(JsonPrimitive("${'$'}{env:TELEGRAM_TOKEN}"), work?.get("botToken"))
        assertEquals(
            listOf("Paired through the environment", "Now?", "Still here"),
            submitter.submissions.map { (it as Submission.Message).message.text },
        )
    }

    /** A plugin that binds [submitter] as the runtime's TurnSubmitter. */
    private class SubmitterIndex(private val submitter: TurnSubmitter) : PluginIndex {
        override val info = PluginInfo(
            "submitter",
            "Submitter",
            "test",
            "",
            AlexandriteSdk.API_VERSION,
            emptyList(),
            SubmitterIndex::class.java.name,
        )
        override val configRoot = "plugins.submitter"

        override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()

        override fun bindings(): List<Binding<*>> =
            listOf(instanceBinding(key<TurnSubmitter>(), submitter, "submitter", "RecordingTurnSubmitter"))
    }

    private companion object {
        val CODE = Regex("[0-9A-Z]{4}-[0-9A-Z]{4}")
    }
}
