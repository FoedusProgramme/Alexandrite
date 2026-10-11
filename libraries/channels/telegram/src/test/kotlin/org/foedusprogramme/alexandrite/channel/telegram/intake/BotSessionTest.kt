package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.channel.telegram.TelegramBots
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.WORK
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
import org.foedusprogramme.alexandrite.channel.telegram.logged
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class BotSessionTest {
    private val api = MemoryBotApi()
    private val bots = TelegramBots()
    private val control = RecordingControl()
    private val home = ChannelInstanceId(ChannelType("telegram"), "home")
    private val bot = FAKE_BOT_TOKEN.substringBefore(':')

    private fun TestScope.session(instance: ChannelInstanceId = WORK): BotSession =
        BotSession(TestInstance(backgroundScope, instance), testConfig(), api, bots, control)

    @Test
    fun `the start learns the bot and claims it for the instance`() = runTest {
        val session = session()

        session.onStart()

        assertEquals(1234567890L to "alexandrite_test_bot", session.identity.run { id to username })
        assertEquals(WORK, bots.claim(bot, home))
        session.onStop()
        assertNull(bots.claim(bot, home))
    }

    @Test
    fun `Telegram out of reach is tried three times, a second and two seconds apart, then fails the start`() = runTest {
        repeat(3) { api.starts += BotApiFailure.TRANSIENT }

        val error = assertFailsWith<IOException> { session().onStart() }

        assertEquals(3000, currentTime)
        assertContains(error.message.orEmpty(), "telegram:work cannot reach Telegram after 3 tries")
        assertFalse(FAKE_BOT_TOKEN.substringAfter(':') in error.message.orEmpty())
        assertNull(bots.claim(bot, home))
    }

    @Test
    fun `a failure that passes costs one more try of that call`() = runTest {
        api.starts += BotApiFailure.TRANSIENT
        api.starts += null
        api.starts += BotApiFailure.RATE_LIMITED

        session().onStart()

        assertEquals(2000, currentTime)
    }

    @Test
    fun `a token Telegram does not accept is a config error at once`() = runTest {
        api.starts += BotApiFailure.UNAUTHORIZED

        val error = assertFailsWith<ConfigException> { session().onStart() }

        assertEquals("channels.telegram.instances.work.botToken", error.path)
        assertContains(error.message.orEmpty(), "Telegram does not accept this bot token")
        assertEquals(0, currentTime)
    }

    @Test
    fun `a webhook and a bot that another instance polls are config errors`() = runTest {
        api.webhook = "https://hooks.example.com/telegram"
        assertEquals("channels.telegram.instances.work", assertFailsWith<ConfigException> { session().onStart() }.path)

        api.webhook = ""
        session().onStart()
        val error = assertFailsWith<ConfigException> { session(home).onStart() }
        assertEquals("channels.telegram.instances.home.botToken", error.path)
        assertContains(error.message.orEmpty(), "is the bot of telegram:work already")
    }

    @Test
    fun `a Bot API reached over plain http on another host is warned of, without the token`() = runTest {
        val urls = listOf(
            "http://botapi.lan:8081",
            "http://localhost:8081",
            "http://127.0.0.1:8081/",
            "http://[::1]:8081",
            "https://botapi.lan",
        )

        val lines = logged {
            for (url in urls) {
                val config = TelegramInstanceConfig(Secret(FAKE_BOT_TOKEN), apiBaseUrl = url)
                BotSession(TestInstance(backgroundScope), config, api, bots, control).apply {
                    onStart()
                    onStop()
                }
            }
        }

        assertEquals(
            listOf(
                "WARN telegram:work reaches the Bot API at http://botapi.lan:8081 over plain http, so its bot token " +
                    "crosses the network unencrypted",
            ),
            lines.filter { "plain http" in it },
        )
        assertFalse(lines.any { FAKE_BOT_TOKEN.substringAfter(':') in it })
    }

    @Test
    fun `a revoked token stops the runtime as a failure once`() = runTest {
        val session = session()

        session.revoked()
        session.revoked()

        val request = control.requests.single()
        assertEquals(StopKind.FAILURE, request.kind)
        assertEquals("Telegram no longer accepts the bot token of telegram:work", request.reason)
    }
}
