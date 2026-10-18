package org.foedusprogramme.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.channel.qqbot.auth.QqBotTokens
import org.foedusprogramme.alexandrite.channel.qqbot.net.QqBotApi
import org.foedusprogramme.alexandrite.channel.qqbot.net.QqBotHttp
import org.foedusprogramme.alexandrite.channel.qqbot.testing.FakeApiServer
import org.foedusprogramme.alexandrite.channel.qqbot.testing.MutableClock
import org.foedusprogramme.alexandrite.channel.qqbot.testing.PlatformAnswer
import org.foedusprogramme.alexandrite.channel.qqbot.testing.PlatformRequest
import org.foedusprogramme.alexandrite.channel.qqbot.testing.testConfig
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QqBotMenuSyncTest {
    private val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))

    @Test
    fun `the slash commands become a single-chat menu and command panels`() = runBlocking {
        val platform = Platform()
        try {
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(200, """{"records":[],"next_cursor":"","is_end":true}""")
                } else {
                    PlatformAnswer(200, """{"panel_id":"p1"}""")
                }
            }
            platform.server.on("/v2/menu", """{"version":1}""")
            QqBotMenuSync(platform.api, platform.config, listOf(Commands)).onOpen()

            val menu = platform.server.calls("/v2/menu").single()
            assertEquals("PUT", menu.method)
            assertTrue("\"name\":\"model\"" in menu.body, menu.body)
            assertTrue("\"send_message\":\"/model\"" in menu.body, menu.body)
            assertTrue("\"desc\":\"Pick the model\"" in platform.server.calls("/v2/panels").last().body)

            val panels = platform.server.calls("/v2/panels")
            assertEquals(4, panels.size)
            assertEquals(listOf("GET", "POST", "GET", "POST"), panels.map { it.method })
            assertTrue("\"scope\":\"c2c\"" in panels[1].body, panels[1].body)
            assertTrue("\"scope\":\"group\"" in panels[3].body, panels[3].body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `the sync language picks the description the handler gives it`() = runBlocking {
        val platform = Platform("zh-CN")
        try {
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(200, """{"records":[],"next_cursor":"","is_end":true}""")
                } else {
                    PlatformAnswer(200, """{"panel_id":"p1"}""")
                }
            }
            platform.server.on("/v2/menu", """{"version":1}""")
            QqBotMenuSync(platform.api, platform.config, listOf(Commands)).onOpen()

            assertTrue("\"desc\":\"选模型\"" in platform.server.calls("/v2/panels").last().body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `an already published panel is updated instead of created again`() = runBlocking {
        val platform = Platform()
        try {
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(
                        200,
                        """{"records":[{"panel_id":"p1","panel":{"remark":"alexandrite commands"}}],"is_end":true}""",
                    )
                } else {
                    PlatformAnswer(200, """{"id":"p1"}""")
                }
            }
            platform.server.on("/v2/menu", """{"version":2}""")
            platform.server.on("/v2/panels/p1", """{"version":2}""")
            QqBotMenuSync(platform.api, platform.config, listOf(Commands)).onOpen()

            val calls = platform.server.calls("/v2/panels/p1")
            assertEquals(2, calls.size)
            assertTrue(calls.all { it.method == "PUT" }, calls.joinToString { it.method })
        } finally {
            platform.close()
        }
    }

    private object Commands : CommandHandler {
        override val commands: List<CommandSpec> = listOf(
            CommandSpec.builder("model", "Pick the model").build(),
            CommandSpec.builder("whoami", "Show the chat and sender").build(),
        )

        override fun description(
            command: String,
            language: org.foedusprogramme.alexandrite.sdk.chat.LanguageTag?,
        ): String = if (language?.value?.startsWith("zh") == true) {
            when (command) {
                "model" -> "选模型"
                else -> "查看 $command"
            }
        } else {
            commands.first { it.name == command }.description
        }

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) =
            throw UnsupportedOperationException("not used")
    }

    private inner class Platform(private val syncLanguage: String? = null) : AutoCloseable {
        val server = FakeApiServer()
        private val http: QqBotHttp
        val api: QqBotApi
        val config = testConfig(server.base, syncLanguage = syncLanguage)

        init {
            server.on("/app/getAppAccessToken", """{"access_token":"access","expires_in":"7200"}""")
            http = QqBotHttp(config)
            api = QqBotApi(http, QqBotTokens(config, http, clock))
        }

        override fun close() {
            http.close()
            server.close()
        }
    }
}
