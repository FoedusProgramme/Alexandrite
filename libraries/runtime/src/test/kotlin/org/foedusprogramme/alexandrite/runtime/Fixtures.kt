package org.foedusprogramme.alexandrite.runtime

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.config.JsonConfigSource
import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.di.Dependency
import org.foedusprogramme.alexandrite.sdk.di.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Resolver
import org.foedusprogramme.alexandrite.sdk.di.Scope
import org.foedusprogramme.alexandrite.sdk.di.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

class Events {
    private val events = Collections.synchronizedList(mutableListOf<String>())

    fun record(event: String) {
        events += event
    }

    fun all(): List<String> = synchronized(events) { events.toList() }
}

class Service(val name: String, private val events: Events, private val start: suspend () -> Unit) : Lifecycle {
    override suspend fun onStart() {
        events.record("start $name")
        start()
    }

    override fun onStop() = events.record("stop $name")

    override fun onDestroy() = events.record("destroy $name")
}

val hang: suspend () -> Unit = { awaitCancellation() }

fun service(
    name: String,
    plugin: String,
    events: Events = Events(),
    dependencies: List<String> = emptyList(),
    scope: Scope = Scope.SINGLETON,
    key: Key<Service> = key(name),
    onStart: suspend () -> Unit = {},
): Binding<Service> = binding(
    key,
    plugin,
    name,
    scope,
    dependencies.map { Dependency(key<Service>(it), DependencyKind.INSTANCE, it) },
) { r ->
    dependencies.forEach { r.get(key<Service>(it)) }
    events.record("create $name")
    Service(name, events, onStart)
}

class Worker(
    private val name: String,
    private val events: Events,
    val control: RuntimeControl,
    private val start: suspend (RuntimeControl) -> Unit,
    private val open: suspend (RuntimeControl) -> Unit,
    private val close: suspend (RuntimeControl) -> Unit,
    private val drain: suspend (RuntimeControl) -> Unit,
    private val stop: (RuntimeControl) -> Unit,
) : Lifecycle {
    override suspend fun onStart() {
        events.record("start $name")
        start(control)
    }

    override suspend fun onOpen() {
        events.record("open $name")
        open(control)
    }

    override suspend fun onClose() {
        events.record("close $name")
        close(control)
    }

    override suspend fun onDrain() {
        events.record("drain $name")
        drain(control)
    }

    override fun onStop() {
        events.record("stop $name")
        stop(control)
    }

    override fun onDestroy() = events.record("destroy $name")
}

fun worker(
    name: String,
    plugin: String,
    events: Events = Events(),
    dependencies: List<String> = emptyList(),
    onStart: suspend (RuntimeControl) -> Unit = {},
    onOpen: suspend (RuntimeControl) -> Unit = {},
    onClose: suspend (RuntimeControl) -> Unit = {},
    onDrain: suspend (RuntimeControl) -> Unit = {},
    onStop: (RuntimeControl) -> Unit = {},
): Binding<Worker> = binding(
    key(name),
    plugin,
    name,
    dependencies = dependencies.map { Dependency(key<Worker>(it), DependencyKind.INSTANCE, it) } +
        Dependency(key<RuntimeControl>(), DependencyKind.INSTANCE, "control"),
) { r ->
    dependencies.forEach { r.get(key<Worker>(it)) }
    events.record("create $name")
    Worker(name, events, r.get(key()), onStart, onOpen, onClose, onDrain, onStop)
}

val HOST_STOP = StopRequest(StopKind.SHUTDOWN, "requested by the host")

@HostApi
class Probe(val values: Map<String, Any>)

fun probe(plugin: String, vararg dependencies: Pair<String, Key<*>>): Binding<Probe> = binding(
    key<Probe>(),
    plugin,
    "Probe",
    dependencies = dependencies.map { (name, key) -> Dependency(key, DependencyKind.INSTANCE, name) },
) { r -> Probe(dependencies.associate { (name, key) -> name to r.resolve(key) }) }

@Suppress("UNCHECKED_CAST")
private fun Resolver.resolve(key: Key<*>): Any = get(key as Key<Any>)

open class TestIndex(
    id: String,
    override val configRoot: String = PluginIds.thirdPartyRoot(id),
    private val bindings: List<Binding<*>> = emptyList(),
    private val sections: List<ConfigSectionSpec<*>> = emptyList(),
    requires: List<String> = emptyList(),
) : PluginIndex {
    override val info: PluginInfo =
        PluginInfo(id, id, "1.0", "", AlexandriteSdk.API_VERSION, requires, javaClass.name)

    override fun bindings(): List<Binding<*>> = bindings

    override fun configSections(): List<ConfigSectionSpec<*>> = sections
}

class AgentIndex : TestIndex("alexandrite-agent", "agent")

class ToolsIndex : TestIndex("alexandrite-tools", "tools")

class NestedToolsIndex : TestIndex("alexandrite-tools-nested", "tools.nested")

class TwinToolsIndex : TestIndex("alexandrite-tools-twin", "tools")

class AppIndex : TestIndex("alexandrite-app", "app")

class TelegramIndex : TestIndex("alexandrite-channel-telegram", "channels.telegram")

class AnthropicIndex : TestIndex("alexandrite-provider-anthropic", "providers.anthropic")

class BotIndex :
    TestIndex(
        "alexandrite-channel-bot",
        "channels.bot",
        listOf(
            service("bot", "alexandrite-channel-bot", dependencies = listOf("token"), scope = Scope.CHANNEL_INSTANCE),
        ),
    )

class RenamedIndex : TestIndex("alexandrite-channel-renamed", "channels.discord")

class MovedIndex : TestIndex("alexandrite-channel-discord", "channels.moved")

class BrokenIndex : TestIndex("alexandrite-broken", "broken") {
    init {
        error("broken")
    }
}

class ExplodingIndex : TestIndex("exploding") {
    init {
        error("an unlisted index was instantiated")
    }
}

class HelloIndex : TestIndex("hello")

class SpyIndex : TestIndex("spy")

const val MISSING_INDEX = "org.example.MissingIndex"

private fun row(index: KClass<out PluginIndex>, id: String, layer: BuiltInLayer, configRoot: String) =
    BuiltInPlugin(index.java.name, id, layer, configRoot)

private val testBuiltIns: List<BuiltInPlugin> = listOf(
    row(AgentIndex::class, "alexandrite-agent", BuiltInLayer.AGENT, "agent"),
    row(ToolsIndex::class, "alexandrite-tools", BuiltInLayer.TOOLS, "tools"),
    row(NestedToolsIndex::class, "alexandrite-tools-nested", BuiltInLayer.TOOLS, "tools.nested"),
    row(TwinToolsIndex::class, "alexandrite-tools-twin", BuiltInLayer.TOOLS, "tools"),
    row(AppIndex::class, "alexandrite-app", BuiltInLayer.APP, "app"),
    row(TelegramIndex::class, "alexandrite-channel-telegram", BuiltInLayer.CHANNEL, "channels.telegram"),
    row(AnthropicIndex::class, "alexandrite-provider-anthropic", BuiltInLayer.PROVIDER, "providers.anthropic"),
    row(BotIndex::class, "alexandrite-channel-bot", BuiltInLayer.CHANNEL, "channels.bot"),
    row(RenamedIndex::class, "alexandrite-channel-discord", BuiltInLayer.CHANNEL, "channels.discord"),
    row(MovedIndex::class, "alexandrite-channel-discord", BuiltInLayer.CHANNEL, "channels.discord"),
    row(BrokenIndex::class, "alexandrite-broken", BuiltInLayer.AGENT, "broken"),
    BuiltInPlugin(MISSING_INDEX, "alexandrite-missing", BuiltInLayer.AGENT, "missing"),
)

fun useTestBuiltIns() {
    BuiltIns.rows = testBuiltIns
}

fun loaded(index: PluginIndex, layer: BuiltInLayer? = null): LoadedPlugin =
    LoadedPlugin(index.info, layer, index.configRoot)

fun names(vararg classes: KClass<out PluginIndex>): List<String> = classes.map { it.java.name }

class Jar(val services: List<String> = emptyList(), val descriptors: Map<String, String> = emptyMap())

fun classPath(directory: Path, vararg jars: Jar): URLClassLoader {
    useTestBuiltIns()
    val roots = jars.mapIndexed { number, jar ->
        val root = directory.resolve("jar$number")
        val services = root.resolve("META-INF/services/${PluginIndex::class.java.name}")
        Files.createDirectories(services.parent)
        Files.writeString(services, jar.services.joinToString("") { "$it\n" })
        for ((id, indexClass) in jar.descriptors) {
            val descriptor = root.resolve("META-INF/alexandrite/$id.json")
            Files.createDirectories(descriptor.parent)
            Files.writeString(descriptor, """{"id": "$id", "indexClass": "$indexClass"}""")
        }
        root.toUri().toURL()
    }
    return URLClassLoader(roots.toTypedArray(), PluginIndex::class.java.classLoader)
}

fun builtIn(directory: Path, vararg classes: KClass<out PluginIndex>): PluginSet =
    classPath(directory.resolve("classes"), Jar(names(*classes))).use { PluginSet.builtIn(it) }

fun explicit(vararg indexes: PluginIndex): PluginSet {
    useTestBuiltIns()
    return PluginSet.of(*indexes)
}

class Recorder : RuntimeListener {
    val events: MutableList<RuntimeEvent> = CopyOnWriteArrayList()

    override fun onEvent(event: RuntimeEvent) {
        events += event
    }

    fun names(): List<String> = events.map { it::class.simpleName.orEmpty() }

    fun termination(): Termination = events.filterIsInstance<RuntimeEvent.Stopped>().single().termination

    fun resolved(): RuntimeEvent.PluginsResolved = events.filterIsInstance<RuntimeEvent.PluginsResolved>().single()
}

fun runtime(
    plugins: PluginSet,
    dataDir: Path,
    config: String = "{}",
    listener: RuntimeListener = RuntimeListener {},
    startTimeout: Duration = 30.seconds,
    shutdownGrace: Duration = 15.seconds,
    zone: ZoneId = ZONE,
    source: ConfigSource = JsonConfigSource(Json.parseToJsonElement(config).jsonObject),
): AlexandriteRuntime {
    val runtimeConfig = RuntimeConfig.builder(dataDir)
        .zone(zone)
        .shutdownGrace(shutdownGrace)
        .startTimeout(startTimeout)
        .name("test")
        .build()
    return AlexandriteRuntime(
        RuntimeSpec.builder(runtimeConfig, plugins)
            .pluginConfig(source)
            .listener(listener)
            .build(),
    )
}

fun AlexandriteRuntime.started(): AlexandriteRuntime = apply { runBlocking { start() } }

fun AlexandriteRuntime.startFailure(): RuntimeStartException = assertFailsWith { runBlocking { start() } }

fun AlexandriteRuntime.stopped(request: StopRequest = HOST_STOP): Termination = runBlocking { stop(request) }

fun AlexandriteRuntime.terminated(): Termination = runBlocking { awaitTermination() }

fun logged(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.map { "${it.level} ${it.formattedMessage}" }
}
