package org.foedusprogramme.alexandrite.automation.heartbeat

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.automation.common.TimeSource
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

val ORIGIN: Instant = Instant.parse("2026-10-18T10:00:00Z")

/** Time that stands still until [advance] moves it. */
class ManualTime(start: Instant = ORIGIN) : TimeSource {
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

    fun advance(by: Duration) {
        clock.update { it.plus(by.toJavaDuration()) }
    }

    /** Suspends until [sleepers] coroutines sleep until a time still to come. */
    suspend fun settle(sleepers: Int) {
        withTimeout(WAIT) {
            combine(clock, deadlines) { now, due -> due.count { it > now } }.first { it >= sleepers }
        }
    }
}

/** The plugin `virtual-time`, which binds [time] as the runtime's TimeSource. */
class TimePlugin(time: TimeSource) : PluginIndex {
    override val info: PluginInfo =
        PluginInfo("virtual-time", "Virtual time", "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
    override val configRoot: String = PluginIds.thirdPartyRoot(info.id)
    private val bindings = listOf(instanceBinding(key<TimeSource>(), time, info.id, "TimeSource"))

    override fun bindings(): List<Binding<*>> = bindings

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
}

fun heartbeatHarness(config: String?, configure: PluginHarness.Builder.() -> Unit): PluginHarness =
    PluginHarness.builder(AlexandriteAutomationHeartbeatIndex())
        .apply { config?.let(::config) }
        .apply(configure)
        .build()

fun PluginHarness.execute(block: suspend PluginHarness.Running.() -> Unit) {
    runBlocking { withTimeout(30.seconds) { run(block) } }
}

/** The lines the plugin logged at INFO and above while [block] ran, as `LEVEL message`. */
fun logged(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger("org.foedusprogramme.alexandrite.automation.heartbeat") as Logger
    val lines = CopyOnWriteArrayList<String>()
    val recorder = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            if (event.level.isGreaterOrEqual(ch.qos.logback.classic.Level.INFO)) {
                lines += "${event.level} ${event.formattedMessage}"
            }
        }
    }.apply { start() }
    logger.addAppender(recorder)
    try {
        block()
    } finally {
        logger.detachAppender(recorder)
    }
    return lines.toList()
}

val WAIT: Duration = 10.seconds

/** How long a beat whose turn ended takes to end itself, at most. */
val WIND_DOWN: Duration = 50.milliseconds
