@file:OptIn(InternalAutomationApi::class)

package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOB
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.GROUP
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.automation.common.InternalAutomationApi
import org.unitedsoftware.alexandrite.automation.common.TimeSource
import org.unitedsoftware.alexandrite.automation.heartbeat.AlexandriteAutomationHeartbeatIndex
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** A heartbeat over a fake Bot API: it tells the agent's home chat unasked, or says nothing at all. */
class TelegramHeartbeatTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()
    private val time = StillTime(Instant.parse("2026-10-18T10:00:00Z"))

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `a beat writes to the home chat through chat_send, and a beat with nothing to say sends nothing`() {
        app.model.reply { toolCall("chat.send", """{"to": "home", "text": "The nightly build failed."}""") }
            .reply { text("") }
            .reply { text("") }
        val heartbeat = AlexandriteAutomationHeartbeatIndex()
        val builder = app.builder(directory)
            .config(AGENT_CONFIG)
            .plugin(heartbeat)
            .config(heartbeat, """{"agents": {"coder": {"every": "30m"}}}""")
            .plugin(TimePlugin(time))

        app.run(builder) {
            time.awaitSleeper()
            time.advance(30)
            app.message(app.adaChat, "The nightly build failed.")
            withTimeout(WAIT) { app.model.awaitRequests(2) }
            idle()
            delay(100.milliseconds)
            time.awaitSleeper()
            time.advance(30)
            withTimeout(WAIT) { app.model.awaitRequests(3) }
            idle()
        }

        assertEquals(listOf("The nightly build failed."), app.fake.botMessages(ADA).map { it.text })
        assertEquals(emptyList(), app.fake.botMessages(BOB) + app.fake.botMessages(GROUP))
        val sent = app.model.requests[1].history.filterIsInstance<ToolResultEntry>().single()
        val result = sent.content.joinToString("") { (it as TextPart).text }
        assertTrue(result.startsWith("Sent to telegram:work:$ADA"), result)
    }

    /** Time that stands still until [advance] moves it. */
    private class StillTime(start: Instant) : TimeSource {
        private val clock = MutableStateFlow(start)
        private val deadlines = MutableStateFlow<List<Instant>>(emptyList())

        override fun now(): Instant = clock.value

        override suspend fun sleepUntil(instant: Instant) {
            if (clock.value >= instant) return
            deadlines.update { it + instant }
            try {
                clock.first { it >= instant }
            } finally {
                deadlines.update { it - instant }
            }
        }

        /** Suspends until a coroutine sleeps until a time still to come. */
        suspend fun awaitSleeper() {
            withTimeout(WAIT) { combine(clock, deadlines) { now, due -> due.any { it > now } }.first { it } }
        }

        fun advance(minutes: Int) {
            clock.update { it.plus(minutes.minutes.toJavaDuration()) }
        }
    }

    /** The plugin `virtual-time`, which binds [time] as the runtime's TimeSource. */
    private class TimePlugin(time: TimeSource) : PluginIndex {
        override val info: PluginInfo =
            PluginInfo(
                "virtual-time",
                "Virtual time",
                "1.0",
                "",
                AlexandriteSdk.API_VERSION,
                emptyList(),
                "test.Plugin",
            )
        override val configRoot: String = PluginIds.thirdPartyRoot(info.id)
        private val bindings = listOf(instanceBinding(key<TimeSource>(), time, info.id, "TimeSource"))

        override fun bindings(): List<Binding<*>> = bindings

        override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
    }

    private companion object {
        val AGENT_CONFIG = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "name": "Coder",
                  "model": "scripted/test-model",
                  "channels": {"telegram:work": {"home": "$ADA"}}
                }
              }
            }
        """.trimIndent()
    }
}
