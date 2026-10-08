package org.foedusprogramme.alexandrite.ksp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Container
import org.foedusprogramme.alexandrite.sdk.di.container.DiException
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexTest {
    @TempDir
    lateinit var workingDir: File

    private val sampleConfig = source(
        "Config.kt",
        """
        package sample.plugin.config

        import kotlinx.serialization.Serializable
        import org.foedusprogramme.alexandrite.sdk.config.ConfigSection

        @ConfigSection
        @Serializable
        data class SampleConfig(val loud: Boolean = false)

        @ConfigSection("greeting")
        @Serializable
        data class GreetingConfig(val word: String = "Hello")
        """,
    )

    private val sample = source(
        "Sample.kt",
        """
        package sample.plugin

        import org.foedusprogramme.alexandrite.ksp.Probe
        import org.foedusprogramme.alexandrite.sdk.di.Binds
        import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
        import org.foedusprogramme.alexandrite.sdk.di.Contribute
        import org.foedusprogramme.alexandrite.sdk.di.Inject
        import org.foedusprogramme.alexandrite.sdk.di.Named
        import org.foedusprogramme.alexandrite.sdk.di.Provides
        import org.foedusprogramme.alexandrite.sdk.di.Singleton
        import org.foedusprogramme.alexandrite.sdk.plugin.Plugin
        import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
        import sample.plugin.config.GreetingConfig
        import sample.plugin.config.SampleConfig

        @Plugin(
            name = "Sample plugin",
            description = "Greets \"people\"\n\${'$'}politely",
            requires = ["alexandrite-agent", "weather"],
        )
        @Singleton
        class SamplePlugin(val info: PluginInfo)

        interface Clock {
            fun now(): String
        }

        @Binds(Clock::class)
        class FixedClock : Clock {
            override fun now(): String = "noon"
        }

        interface Greeter {
            fun greet(name: String): String
        }

        @Singleton
        @Named("polite")
        @Binds(Greeter::class)
        class PoliteGreeter(
            private val config: GreetingConfig,
            private val sample: SampleConfig,
            private val clock: () -> Clock,
        ) : Greeter {
            override fun greet(name: String): String =
                config.word + " " + name + ", it is " + clock().now() + if (sample.loud) "!" else ""
        }

        @Contribute(Probe::class)
        class EchoTool : Probe {
            override fun report(): String = "echo"
        }

        @Contribute(Probe::class)
        class TimeTool(private val clock: Lazy<Clock>) : Probe {
            override fun report(): String = "time " + clock.value.now()
        }

        interface Audit

        @Binds(Map::class)
        class Limits : Map<String, Int> by mapOf("rounds" to 3)

        @Singleton
        @Named("app")
        @Binds(Probe::class)
        class App @Inject constructor(
            @Named("polite") private val greeter: Greeter,
            @Named("user") private val user: String,
            private val tools: List<Probe>,
            private val audit: Audit?,
            private val limits: Map<String, Int>,
        ) : Probe {
            constructor(greeter: Greeter) : this(greeter, "nobody", emptyList(), null, emptyMap())

            override fun report(): String = greeter.greet(user) + "; tools=" + tools.map { it.report() } +
                "; audit=" + audit + "; rounds=" + limits["rounds"]
        }

        @ChannelInstanceScoped
        @Named("session")
        @Binds(Probe::class)
        class Session(@Named("app") private val app: Probe) : Probe {
            override fun report(): String = "session of " + app.report()
        }

        typealias Probes = List<Probe>
        typealias Handlers<T> = Map<String, Handler<T>>
        typealias MaybeClock = Clock?

        fun interface Handler<T> {
            fun handle(value: T): String
        }

        @Provides
        fun handlers(): Handlers<Int> = mapOf("double" to Handler { (it * 2).toString() })

        @Singleton
        @Named("aliased")
        @Binds(Probe::class)
        class Aliased(private val probes: Probes, private val handlers: Handlers<Int>, private val clock: MaybeClock) :
            Probe {
            override fun report(): String =
                "probes=" + probes.size + "; double=" + handlers.getValue("double").handle(21) + "; clock=" + clock?.now()
        }
        """,
    )

    private val config = Json.parseToJsonElement(
        """{"plugins": {"sample-plugin": {"enabled": true, "loud": true, "greeting": {"word": "Hi"}}}}""",
    ).jsonObject

    private val compiled by lazy {
        compile(sharedDir, sampleConfig, sample, options = sampleOptions("sample-plugin")).also {
            it.assertSucceeded()
        }
    }

    private val index: PluginIndex by lazy { compiled.indexes().single() }

    @AfterAll
    fun closeSample() {
        compiled.close()
    }

    private fun overrides(index: PluginIndex): List<Binding<*>> = sectionBindings(index, config) +
        instanceBinding(key<String>("user"), "Ada", "test", "test") +
        instanceBinding(key<PluginInfo>(index.info.id), index.info, "test", "test")

    // Identity.

    @Test
    fun `ServiceLoader finds the generated index in the plugin's package`() {
        assertEquals("sample.plugin.SamplePluginIndex", index.javaClass.name)
        assertEquals("sample.plugin.SamplePluginIndex\n", compiled.service())
        assertEquals("plugins.sample-plugin", index.configRoot)
    }

    @Test
    fun `the index carries what the plugin says about itself`() {
        assertEquals(
            PluginInfo(
                id = "sample-plugin",
                name = "Sample plugin",
                version = "1.0.0",
                description = "Greets \"people\"\n\$politely",
                sdkApi = AlexandriteSdk.API_VERSION,
                requires = listOf("alexandrite-agent", "weather"),
                entryClass = "sample.plugin.SamplePlugin",
            ),
            index.info,
        )
    }

    @Test
    fun `the descriptor holds every field in a stable order`() {
        val descriptor = compiled.descriptor("sample-plugin")

        assertEquals(
            listOf(
                "id", "name", "version", "description", "sdkApi", "requires", "entryClass", "channelType",
                "indexClass", "configRoot", "builtIn",
            ),
            descriptor.keys.toList(),
        )
        assertEquals(JsonPrimitive("sample-plugin"), descriptor["id"])
        assertEquals(JsonPrimitive("Sample plugin"), descriptor["name"])
        assertEquals(JsonPrimitive("1.0.0"), descriptor["version"])
        assertEquals(JsonPrimitive("Greets \"people\"\n\$politely"), descriptor["description"])
        assertEquals(JsonPrimitive(AlexandriteSdk.API_VERSION), descriptor["sdkApi"])
        assertEquals(
            JsonArray(listOf(JsonPrimitive("alexandrite-agent"), JsonPrimitive("weather"))),
            descriptor["requires"],
        )
        assertEquals(JsonPrimitive("sample.plugin.SamplePlugin"), descriptor["entryClass"])
        assertEquals(JsonNull, descriptor["channelType"])
        assertEquals(JsonPrimitive("sample.plugin.SamplePluginIndex"), descriptor["indexClass"])
        assertEquals(JsonPrimitive("plugins.sample-plugin"), descriptor["configRoot"])
        assertEquals(JsonPrimitive(false), descriptor["builtIn"])
    }

    // Container.

    @Test
    fun `the container resolves the generated graph with the decoded config sections`() {
        Container.build(listOf(index.pluginBindings()), overrides(index)).use { container ->
            assertEquals(
                "Hi Ada, it is noon!; tools=[echo, time noon]; audit=null; rounds=3",
                container.get(key<Probe>("app")).report(),
            )
            assertEquals(listOf("echo", "time noon"), container.getAll(key<Probe>()).map { it.report() })
        }
    }

    @Test
    fun `the entry class is a singleton that injects its own plugin info`() {
        Container.build(listOf(index.pluginBindings()), overrides(index)).use { container ->
            val entry = compiled.classLoader.loadClass("sample.plugin.SamplePlugin")
            val binding = index.bindings().single { it.origin == "sample.plugin.SamplePlugin" }

            assertEquals(listOf(key<PluginInfo>("sample-plugin")), binding.dependencies.map { it.key })
            val instance = container.get(binding.key)
            assertEquals(entry, instance.javaClass)
            assertEquals(index.info, entry.getMethod("getInfo").invoke(instance))
        }
    }

    @Test
    fun `aliases are expanded with their arguments and nullability`() {
        Container.build(listOf(index.pluginBindings()), overrides(index)).use { container ->
            assertEquals("probes=2; double=42; clock=noon", container.get(key<Probe>("aliased")).report())
        }
        val binding = index.bindings().first { it.origin == "sample.plugin.Aliased" }
        assertEquals(
            listOf(
                "probes: ALL ${Probe::class.java.name}",
                "handlers: INSTANCE kotlin.collections.Map<kotlin.String, sample.plugin.Handler<kotlin.Int>>",
                "clock: OPTIONAL sample.plugin.Clock",
            ),
            binding.dependencies.map { "${it.site}: ${it.kind} ${it.key}" },
        )
    }

    @Test
    fun `a channel instance container creates its own channel-instance-scoped instance`() {
        Container.build(listOf(index.pluginBindings()), overrides(index)).use { container ->
            val telegram = container.child("telegram", setOf("sample-plugin"))
            val session = telegram.get(key<Probe>("session"))

            assertEquals("session of " + container.get(key<Probe>("app")).report(), session.report())
            assertSame(session, telegram.get(key<Probe>("session")))
            assertNotSame(session, container.child("discord", setOf("sample-plugin")).get(key<Probe>("session")))
            assertFailsWith<DiException> { container.get(key<Probe>("session")) }
        }
    }

    @Test
    fun `the index binds no config section itself`() {
        val error = assertFailsWith<DiException> {
            Container.build(
                listOf(index.pluginBindings()),
                listOf(
                    instanceBinding(key<String>("user"), "Ada", "test", "test"),
                    instanceBinding(key<PluginInfo>(index.info.id), index.info, "test", "test"),
                ),
            )
        }
        assertContains(error.message!!, "nothing binds sample.plugin.config.GreetingConfig")
    }

    // Bindings and sections.

    @Test
    fun `the index declares every binding with its origin, scope, flags and dependencies`() {
        val probe = Probe::class.java.name

        assertEquals(
            mapOf(
                "Aliased" to listOf(
                    "@Named(\"aliased\") sample.plugin.Aliased SINGLETON <- probes: ALL $probe, handlers: INSTANCE " +
                        "kotlin.collections.Map<kotlin.String, sample.plugin.Handler<kotlin.Int>>, " +
                        "clock: OPTIONAL sample.plugin.Clock",
                    "@Named(\"aliased\") $probe SINGLETON unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"aliased\") sample.plugin.Aliased",
                ),
                "App" to listOf(
                    "@Named(\"app\") sample.plugin.App SINGLETON <- " +
                        "greeter: INSTANCE @Named(\"polite\") sample.plugin.Greeter, " +
                        "user: INSTANCE @Named(\"user\") kotlin.String, tools: ALL $probe, " +
                        "audit: OPTIONAL sample.plugin.Audit, " +
                        "limits: INSTANCE kotlin.collections.Map<kotlin.String, kotlin.Int>",
                    "@Named(\"app\") $probe SINGLETON unmanaged <- @Binds: INSTANCE @Named(\"app\") sample.plugin.App",
                ),
                "EchoTool" to listOf(
                    "sample.plugin.EchoTool SINGLETON <- ",
                    "$probe SINGLETON multi unmanaged <- @Contribute: INSTANCE sample.plugin.EchoTool",
                ),
                "FixedClock" to listOf(
                    "sample.plugin.FixedClock SINGLETON <- ",
                    "sample.plugin.Clock SINGLETON unmanaged <- @Binds: INSTANCE sample.plugin.FixedClock",
                ),
                "Limits" to listOf(
                    "sample.plugin.Limits SINGLETON <- ",
                    "kotlin.collections.Map<kotlin.String, kotlin.Int> SINGLETON unmanaged <- " +
                        "@Binds: INSTANCE sample.plugin.Limits",
                ),
                "PoliteGreeter" to listOf(
                    "@Named(\"polite\") sample.plugin.PoliteGreeter SINGLETON <- " +
                        "config: INSTANCE sample.plugin.config.GreetingConfig, " +
                        "sample: INSTANCE sample.plugin.config.SampleConfig, clock: PROVIDER sample.plugin.Clock",
                    "@Named(\"polite\") sample.plugin.Greeter SINGLETON unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"polite\") sample.plugin.PoliteGreeter",
                ),
                "SamplePlugin" to listOf(
                    "sample.plugin.SamplePlugin SINGLETON <- info: INSTANCE @Named(\"sample-plugin\") " +
                        PluginInfo::class.java.name,
                ),
                "Session" to listOf(
                    "@Named(\"session\") sample.plugin.Session CHANNEL_INSTANCE <- " +
                        "app: INSTANCE @Named(\"app\") $probe",
                    "@Named(\"session\") $probe CHANNEL_INSTANCE unmanaged <- " +
                        "@Binds: INSTANCE @Named(\"session\") sample.plugin.Session",
                ),
                "TimeTool" to listOf(
                    "sample.plugin.TimeTool SINGLETON <- clock: LAZY sample.plugin.Clock",
                    "$probe SINGLETON multi unmanaged <- @Contribute: INSTANCE sample.plugin.TimeTool",
                ),
                "handlers()" to listOf(
                    "kotlin.collections.Map<kotlin.String, sample.plugin.Handler<kotlin.Int>> SINGLETON <- ",
                ),
            ),
            index.bindings().groupBy({ simpleOrigin(it.origin, "sample.plugin") }, ::describe),
        )
    }

    @Test
    fun `the index lists the config sections with their relative paths`() {
        val sections = index.configSections()

        assertEquals(
            listOf(
                "'' sample.plugin.config.SampleConfig from config.SampleConfig",
                "'greeting' sample.plugin.config.GreetingConfig from config.GreetingConfig",
            ),
            sections.map { "'${it.path}' ${it.key} from ${simpleOrigin(it.origin, "sample.plugin")}" },
        )
        assertEquals(
            listOf("sample.plugin.config.SampleConfig", "sample.plugin.config.GreetingConfig"),
            sections.map { it.deserializer.descriptor.serialName },
        )
    }

    @Test
    fun `section paths are relative to the plugin's config root`() {
        val paths = source(
            "Paths.kt",
            """
            package sample

            import kotlinx.serialization.Serializable
            import org.foedusprogramme.alexandrite.sdk.config.ConfigSection

            @ConfigSection() @Serializable class Root
            @ConfigSection("cache") @Serializable class Cache
            @ConfigSection("cache.disk") @Serializable class Disk
            @ConfigSection("Web-hooks_2.retry") @Serializable class Retry
            @ConfigSection("cache.enabled") @Serializable class CacheSwitch
            """,
        )
        compile(workingDir.resolve("paths"), paths, entry("sample")).use { compiled ->
            compiled.assertSucceeded()

            val index = compiled.indexes().single()
            assertEquals(
                listOf("", "Web-hooks_2.retry", "cache", "cache.disk", "cache.enabled"),
                index.configSections().map { it.path },
            )
            assertEquals("plugins.sample", index.configRoot)
        }
    }

    // Generated code.

    @Test
    fun `a plugin with only its entry class gets an index with the entry's binding`() {
        compile(workingDir.resolve("entry"), entry("sample")).use { compiled ->
            compiled.assertSucceeded()

            val index = compiled.indexes().single()
            assertEquals(listOf("sample.SamplePlugin"), index.bindings().map { it.origin })
            assertEquals(emptyList(), index.configSections())
            Container.build(listOf(index.pluginBindings())).close()
        }
    }

    @Test
    fun `a component with an explicit backing field is indexed`() {
        val ledger = source(
            "Ledger.kt",
            """
            package sample

            import org.foedusprogramme.alexandrite.sdk.di.Singleton

            @Singleton
            class Ledger {
                val entries: List<String>
                    field = mutableListOf()
            }
            """,
        )
        compile(workingDir.resolve("backing-field"), ledger, entry("sample")).use { compiled ->
            compiled.assertSucceeded()

            assertEquals(
                listOf("sample.Ledger", "sample.SamplePlugin"),
                compiled.indexes().single().bindings().map { it.origin }.sorted(),
            )
        }
    }

    @Test
    fun `the index compiles in explicit API strict mode`() {
        val explicit = source(
            "Explicit.kt",
            """
            package sample

            import kotlinx.serialization.Serializable
            import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
            import org.foedusprogramme.alexandrite.sdk.di.Singleton
            import org.foedusprogramme.alexandrite.sdk.plugin.Plugin

            @Plugin(name = "Explicit")
            public class ExplicitPlugin

            @Singleton
            public class Clock

            @Singleton
            internal class Greeter(private val clock: Clock, private val config: GreeterConfig)

            @ConfigSection
            @Serializable
            internal class GreeterConfig
            """,
        )
        compile(workingDir.resolve("explicit"), explicit, explicitApi = true).use { compiled ->
            compiled.assertSucceeded()

            assertEquals(3, compiled.indexes().single().bindings().size)
            assertEquals(1, compiled.indexes().single().configSections().size)
        }
    }

    @Test
    fun `keywords, backticks and special characters survive into the generated index`() {
        val special = source(
            "Special.kt",
            """
            package sample.`in`

            import org.foedusprogramme.alexandrite.ksp.Probe
            import org.foedusprogramme.alexandrite.sdk.di.Binds
            import org.foedusprogramme.alexandrite.sdk.di.Named
            import org.foedusprogramme.alexandrite.sdk.di.Singleton
            import org.foedusprogramme.alexandrite.sdk.plugin.Plugin

            @Plugin(name = "Quote \" dollar ${'$'} backslash \\ newline \n tab \t")
            class `fun`

            @Singleton
            @Named("a\"b\${'$'}c\nd\\e")
            @Binds(Probe::class)
            class `object`(@Named("x\ty") private val word: String) : Probe {
                override fun report(): String = word
            }
            """,
        )
        compile(workingDir.resolve("special"), special).use { compiled ->
            compiled.assertSucceeded()

            val index = compiled.indexes().single()
            assertEquals("Quote \" dollar \$ backslash \\ newline \n tab \t", index.info.name)
            assertEquals(index.info.name, compiled.descriptor("sample")["name"]?.let { (it as JsonPrimitive).content })
            val word = instanceBinding(key<String>("x\ty"), "special", "test", "test")
            Container.build(listOf(index.pluginBindings()), listOf(word)).use { container ->
                assertEquals("special", container.get(key<Probe>("a\"b\$c\nd\\e")).report())
            }
        }
    }

    @Test
    fun `the generated index compiles in a package whose first segment is a short name`() {
        val shortPackage = source(
            "Short.kt",
            """
            package r.x

            import org.foedusprogramme.alexandrite.sdk.di.Singleton
            import org.foedusprogramme.alexandrite.sdk.plugin.Plugin

            @Plugin(name = "Short")
            class ShortPlugin(val engine: Engine)

            @Singleton
            class Engine
            """,
        )
        compile(workingDir.resolve("short"), shortPackage).use { compiled ->
            compiled.assertSucceeded()

            Container.build(listOf(compiled.indexes().single().pluginBindings())).close()
        }
    }

    @Test
    fun `the generated index compiles in a package named like a member of the index`() {
        for (root in listOf("info", "configRoot")) {
            val weather = source(
                "Weather.kt",
                """
                package $root.weather

                import kotlinx.serialization.Serializable
                import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
                import org.foedusprogramme.alexandrite.sdk.di.Provides
                import org.foedusprogramme.alexandrite.sdk.di.Singleton
                import org.foedusprogramme.alexandrite.sdk.plugin.Plugin

                @Plugin(name = "Weather")
                class WeatherPlugin(val station: Station, val forecast: Forecast)

                @Singleton
                class Station(val config: StationConfig)

                class Forecast

                @Provides
                fun forecast(): Forecast = Forecast()

                @ConfigSection
                @Serializable
                class StationConfig(val name: String = "north")
                """,
            )
            compile(workingDir.resolve(root), weather, options = sampleOptions("weather")).use { compiled ->
                compiled.assertSucceeded()

                val index = compiled.indexes().single()
                val sections = sectionBindings(index, Json.parseToJsonElement("{}").jsonObject)
                Container.build(listOf(index.pluginBindings()), sections).close()
            }
        }
    }

    @Test
    fun `names declared in the index's package do not change what the generated index means`() {
        val shadows = source(
            "Shadows.kt",
            """
            package sample

            import org.foedusprogramme.alexandrite.sdk.di.Singleton

            @Singleton
            class Engine(val clock: java.time.Clock)

            val java = 0
            val info = 0
            class List
            class String
            class Contents
            annotation class OptIn
            annotation class Suppress
            fun listOf(vararg items: Any): Int = items.size
            fun <T> emptyList(): Int = 0
            """,
        )
        compile(workingDir.resolve("shadows"), shadows, entry("sample")).use { compiled ->
            compiled.assertSucceeded()

            val index = compiled.indexes().single()
            assertEquals(listOf("sample.Engine", "sample.SamplePlugin"), index.bindings().map { it.origin })
            assertEquals(emptyList(), index.configSections())
            val clock = instanceBinding(key<java.time.Clock>(), java.time.Clock.systemUTC(), "test", "test")
            Container.build(listOf(index.pluginBindings()), listOf(clock)).close()
        }
    }

    @Test
    fun `deprecated and opt-in components are indexed without warnings`() {
        val marked = source(
            "Marked.kt",
            """
            package sample

            import kotlinx.serialization.Serializable
            import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
            import org.foedusprogramme.alexandrite.sdk.di.Provides
            import org.foedusprogramme.alexandrite.sdk.di.Singleton

            @RequiresOptIn(level = RequiresOptIn.Level.WARNING)
            annotation class Preview

            @RequiresOptIn
            annotation class Unstable

            @Deprecated("Use Engine")
            @Singleton
            class OldEngine

            @Preview
            @Singleton
            class Engine

            @OptIn(Preview::class)
            @Singleton
            class Car(val engine: Engine, val tuning: Tuning)

            class Wheel

            @Unstable
            object Parts {
                @Provides
                fun wheel(): Wheel = Wheel()
            }

            class Horn

            @Deprecated("Gone")
            @Provides
            fun horn(): Horn = Horn()

            @Preview
            @ConfigSection
            @Serializable
            class Tuning(val level: Int = 0)
            """,
        )
        compile(workingDir.resolve("marked"), marked, entry("sample")).use { compiled ->
            compiled.assertSucceeded()

            assertEquals(
                listOf(
                    "sample.Car",
                    "sample.Engine",
                    "sample.OldEngine",
                    "sample.Parts.wheel()",
                    "sample.SamplePlugin",
                    "sample.horn()",
                ),
                compiled.indexes().single().bindings().map { it.origin },
            )
        }
    }

    private companion object {
        @TempDir
        lateinit var sharedDir: File
    }
}
