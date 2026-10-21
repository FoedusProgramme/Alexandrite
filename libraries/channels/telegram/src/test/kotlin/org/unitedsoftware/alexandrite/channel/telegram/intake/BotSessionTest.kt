package org.unitedsoftware.alexandrite.channel.telegram.intake

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.channel.telegram.GroupMessages
import org.unitedsoftware.alexandrite.channel.telegram.TelegramBots
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.WORK
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure
import org.unitedsoftware.alexandrite.channel.telegram.api.User
import org.unitedsoftware.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
import org.unitedsoftware.alexandrite.channel.telegram.logged
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.runtime.StopKind
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
    fun `a token Telegram does not accept is not tried again`() = runTest {
        api.starts += BotApiFailure.UNAUTHORIZED

        assertFailsWith<ConfigException> { session().onStart() }

        assertEquals(0, currentTime)
    }

    @Test
    fun `a bot without admins is warned of at start, one with an admin not`() = runTest {
        suspend fun started(admins: List<Long>): List<String> = logged {
            val config = TelegramInstanceConfig(Secret(FAKE_BOT_TOKEN), adminUserIds = admins)
            BotSession(TestInstance(backgroundScope), config, api, bots, control).apply {
                onStart()
                onStop()
            }
        }.filter { "no admin is configured" in it }

        assertEquals(listOf("WARN"), started(emptyList()).map { it.substringBefore(' ') })
        assertEquals(emptyList(), started(listOf(1001)))
    }

    @Test
    fun `privacy mode is told of at start, as a warning when the bot takes every group message`() = runTest {
        suspend fun started(groupMessages: GroupMessages): List<String> = logged {
            val config = TelegramInstanceConfig(Secret(FAKE_BOT_TOKEN), groupMessages = groupMessages)
            BotSession(TestInstance(backgroundScope), config, api, bots, control).apply {
                onStart()
                onStop()
            }
        }.filter { "privacy mode" in it }.map { it.substringBefore(' ') }

        val addressed = started(GroupMessages.ADDRESSED)
        val all = started(GroupMessages.ALL)
        api.me = User(api.me.id, isBot = true, username = api.me.username, canReadAllGroupMessages = true)
        val open = started(GroupMessages.ALL)

        assertEquals(listOf("INFO"), addressed)
        assertEquals(listOf("WARN"), all)
        assertEquals(emptyList(), open)
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

        assertContains(lines.single { "plain http" in it }, "http://botapi.lan:8081")
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
