package org.foedusprogramme.alexandrite.agent

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.model.Endpoints
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(10.seconds) { block() } }

/** A harness of the agent plugin with [config] below its root. */
fun agentHarness(config: String = "{}", configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness =
    PluginHarness.builder(AlexandriteAgentIndex()).config(config).apply(configure).build()

fun PluginHarness.execute(block: suspend PluginHarness.Running.() -> Unit) {
    blocking { run(block) }
}

/** Asserts that the agent fails to start at [stage] for the one config problem [message]. */
fun PluginHarness.assertStartFails(stage: StartStage, message: String) {
    val error = assertFailsWith<RuntimeStartException> { blocking { run {} } }
    assertEquals(stage to listOf(message), error.stage to error.problems.map { it.message }, error.message)
    assertEquals(listOf("runtime.invalid_config"), error.problems.map { it.kind.id })
}

/** The lines the agent logged while [block] ran, as `LEVEL message`, which [block] sees as they come. */
fun logged(block: (lines: List<String>) -> Unit): List<String> {
    val logger = LoggerFactory.getLogger("org.foedusprogramme.alexandrite.agent") as Logger
    val recorder = LineRecorder().apply { start() }
    logger.addAppender(recorder)
    try {
        block(recorder.lines)
    } finally {
        logger.detachAppender(recorder)
    }
    return recorder.lines.toList()
}

private class LineRecorder : AppenderBase<ILoggingEvent>() {
    val lines = CopyOnWriteArrayList<String>()

    override fun append(event: ILoggingEvent) {
        lines += "${event.level} ${event.formattedMessage}"
    }
}

/** A clock that stands still until it is moved. */
class TestClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

/** A plugin [id] that contributes [providers]. */
class ProvidersIndex(id: String, private val providers: List<ModelProvider>) : PluginIndex {
    override val info = PluginInfo(id, id, "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
    override val configRoot = PluginIds.thirdPartyRoot(id)

    override fun bindings(): List<Binding<*>> = providers.mapIndexed { index, provider ->
        instanceBinding(key<ModelProvider>(), provider, info.id, "provider $index", multi = true)
    }

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
}

fun provider(vararg endpoints: ModelEndpoint): ModelProvider = object : ModelProvider {
    override val endpoints: List<ModelEndpoint> = endpoints.toList()
}

internal fun settings(json: String): AgentSettings = Json.decodeFromString(AgentSettings.serializer(), json)

/** The directory of [settings] where [instances] are configured and [providers] contribute the endpoints. */
internal fun agentDirectory(
    settings: AgentSettings,
    instances: Set<String> = emptySet(),
    providers: List<ModelProvider> = emptyList(),
): AgentDirectory {
    val channels = object : ChannelDirectory {
        override val instances = instances.map(ChannelInstanceId::parse).toSet()

        override fun channel(instance: ChannelInstanceId): Channel? = null
    }
    return AgentDirectory(settings, channels, Endpoints(providers, settings, TestClock()))
}

fun hostPaths(configFile: Path?, dataRoot: Path = Path.of("/data")): HostPaths = object : HostPaths {
    override val dataRoot = dataRoot
    override val cacheRoot: Path = dataRoot.resolve("cache")
    override val configFile = configFile
    override val protected = emptyList<Path>()
}
