package org.foedusprogramme.alexandrite.runtime.config

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.runtime.AgentIndex
import org.foedusprogramme.alexandrite.runtime.AnthropicIndex
import org.foedusprogramme.alexandrite.runtime.AppIndex
import org.foedusprogramme.alexandrite.runtime.HelloIndex
import org.foedusprogramme.alexandrite.runtime.NestedToolsIndex
import org.foedusprogramme.alexandrite.runtime.Probe
import org.foedusprogramme.alexandrite.runtime.Recorder
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.TelegramIndex
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.ToolsIndex
import org.foedusprogramme.alexandrite.runtime.TwinToolsIndex
import org.foedusprogramme.alexandrite.runtime.builtIn
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.loaded
import org.foedusprogramme.alexandrite.runtime.plugin.DisabledPlugin
import org.foedusprogramme.alexandrite.runtime.plugin.DisabledPlugin.Reason.ENABLED_FALSE
import org.foedusprogramme.alexandrite.runtime.plugin.DisabledPlugin.Reason.NOT_CONFIGURED
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.runtime.probe
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConfigTest {
    @TempDir
    lateinit var dataDir: Path

    @Serializable
    data class WeatherConfig(val city: String = "Paris")

    @Serializable
    data class CacheConfig(val size: Int = 10)

    private val cacheSection = ConfigSectionSpec(key<CacheConfig>(), "cache", CacheConfig.serializer(), "CacheConfig")

    private val weatherSections = listOf(
        ConfigSectionSpec(key<WeatherConfig>(), "", WeatherConfig.serializer(), "WeatherConfig"),
        cacheSection,
    )

    private fun weather() = TestIndex(
        "weather",
        bindings = listOf(probe("weather", "weather" to key<WeatherConfig>(), "cache" to key<CacheConfig>())),
        sections = weatherSections,
    )

    private val core = arrayOf(AgentIndex::class, ToolsIndex::class, AppIndex::class)
    private val optional = arrayOf(TelegramIndex::class, AnthropicIndex::class)

    private fun resolved(plugins: PluginSet, config: String): RuntimeEvent.PluginsResolved {
        val recorder = Recorder()
        spec(plugins, dataDir, config, recorder).execute()
        return recorder.resolved()
    }

    private fun failure(plugins: PluginSet, config: String): RuntimeStartException =
        spec(plugins, dataDir, config).startFailure().also { assertEquals(StartStage.CONFIG, it.stage) }

    // Plugin switch.

    @Test
    fun `core plugins are on by default and built-in channels and providers only with a config section`() {
        val plugins = builtIn(dataDir, *core, *optional)

        val bare = resolved(plugins, "{}")
        val configured = resolved(plugins, """{"channels": {"telegram": {}}, "providers": {"anthropic": {}}}""")

        assertEquals(
            listOf("alexandrite-agent", "alexandrite-app", "alexandrite-tools"),
            bare.loaded.map { it.info.id },
        )
        assertEquals(
            listOf(
                DisabledPlugin("alexandrite-channel-telegram", NOT_CONFIGURED),
                DisabledPlugin("alexandrite-provider-anthropic", NOT_CONFIGURED),
            ),
            bare.disabled,
        )
        assertEquals(5, configured.loaded.size)
        assertEquals(emptyList(), configured.disabled)
    }

    @Test
    fun `enabled false switches any plugin off`() {
        val plugins = builtIn(dataDir, AgentIndex::class, TelegramIndex::class) + HelloIndex()
        val config = """
            {"agent": {"enabled": false}, "channels": {"telegram": {"enabled": false}},
             "plugins": {"hello": {"enabled": false}}}
        """

        val resolved = resolved(plugins, config)

        assertEquals(emptyList(), resolved.loaded)
        assertEquals(
            listOf("alexandrite-agent", "alexandrite-channel-telegram", "hello").map {
                DisabledPlugin(it, ENABLED_FALSE)
            },
            resolved.disabled,
        )
    }

    @Test
    fun `explicitly added plugins are on by default whatever their layer`() {
        val resolved = resolved(explicit(TelegramIndex(), AnthropicIndex(), HelloIndex()), "{}")

        assertEquals(
            listOf("alexandrite-channel-telegram", "alexandrite-provider-anthropic", "hello"),
            resolved.loaded.map { it.info.id },
        )
    }

    @Test
    fun `a non-boolean enabled fails naming the full path`() {
        for (value in listOf("\"false\"", "0", "null", "{}", "[]")) {
            val error =
                failure(builtIn(dataDir, TelegramIndex::class), """{"channels": {"telegram": {"enabled": $value}}}""")

            assertEquals(
                listOf(
                    Problem(
                        RuntimeProblemKind.INVALID_CONFIG,
                        "Invalid config at 'channels.telegram.enabled': must be true or false",
                        "alexandrite-channel-telegram",
                    ),
                ),
                error.problems,
                value,
            )
        }
    }

    // Requirements.

    @Test
    fun `a plugin fails when a plugin it requires is not loaded, disabled or not configured`() {
        val weather = TestIndex("weather", requires = listOf("radar", "alexandrite-channel-telegram", "sun", "radar"))
        val plugins = builtIn(dataDir, TelegramIndex::class) + weather + TestIndex("radar")

        val error = failure(plugins, """{"plugins": {"radar": {"enabled": false}}}""")

        assertEquals(
            listOf(
                "Plugin 'weather' requires plugin 'radar', which is disabled by `plugins.radar.enabled = false`.",
                "Plugin 'weather' requires plugin 'alexandrite-channel-telegram', which is not configured. " +
                    "Add a 'channels.telegram' section to switch it on.",
                "Plugin 'weather' requires plugin 'sun', which is not loaded. Add it to the plugin set.",
            ),
            error.problems.map { it.message },
        )
        assertEquals(
            List(3) { RuntimeProblemKind.MISSING_REQUIREMENT to "weather" },
            error.problems.map { it.kind to it.plugin },
        )
    }

    @Test
    fun `requirements hold when the required plugins are enabled, and a disabled plugin requires nothing`() {
        val plugins = explicit(
            TestIndex("weather", requires = listOf("radar")),
            TestIndex("radar"),
            TestIndex("storm", requires = listOf("sun")),
        )

        val resolved = resolved(plugins, """{"plugins": {"storm": {"enabled": false}}}""")

        assertEquals(listOf("radar", "weather"), resolved.loaded.map { it.info.id })
    }

    // Config roots.

    @Test
    fun `config roots of enabled plugins may not be equal or nested`() {
        val error = failure(explicit(ToolsIndex(), NestedToolsIndex(), TwinToolsIndex(), AgentIndex()), "{}")

        assertEquals(
            listOf(
                "Overlapping config roots: plugin 'alexandrite-tools' reads 'tools' and plugin " +
                    "'alexandrite-tools-nested' reads 'tools.nested'. Give each plugin a config root of its own.",
                "Overlapping config roots: plugin 'alexandrite-tools' reads 'tools' and plugin " +
                    "'alexandrite-tools-twin' reads 'tools'. Give each plugin a config root of its own.",
                "Overlapping config roots: plugin 'alexandrite-tools-nested' reads 'tools.nested' and plugin " +
                    "'alexandrite-tools-twin' reads 'tools'. Give each plugin a config root of its own.",
            ),
            error.problems.map { it.message },
        )
        assertEquals(List(3) { RuntimeProblemKind.OVERLAPPING_ROOTS }, error.problems.map { it.kind })
    }

    @Test
    fun `a disabled plugin's root may overlap`() {
        val resolved = resolved(explicit(ToolsIndex(), NestedToolsIndex()), """{"tools": {"enabled": false}}""")

        assertEquals(listOf("alexandrite-tools-nested"), resolved.loaded.map { it.info.id })
        assertEquals(listOf(DisabledPlugin("alexandrite-tools", ENABLED_FALSE)), resolved.disabled)
    }

    @Test
    fun `config that no plugin reads fails listing the config roots`() {
        val plugins = builtIn(dataDir, AgentIndex::class, TelegramIndex::class)
        val config = """{"agnet": {}, "channels": {"telegram": {}, "discord": {}}, "providers": 1, "a.b": {}}"""

        val error = failure(plugins, config)

        val roots = "Config roots of the plugin set: agent, channels.telegram."
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.INVALID_CONFIG,
                    "Invalid config key 'a.b': a key may not be empty or contain '.'. Nest the objects instead.",
                    null,
                ),
                Problem(
                    RuntimeProblemKind.UNKNOWN_CONFIG,
                    "Unknown config at 'agnet': no plugin reads it. $roots",
                    null,
                ),
                Problem(
                    RuntimeProblemKind.UNKNOWN_CONFIG,
                    "Unknown config at 'channels.discord': no plugin reads it. $roots",
                    null,
                ),
                Problem(
                    RuntimeProblemKind.UNKNOWN_CONFIG,
                    "Unknown config at 'providers': no plugin reads it. $roots",
                    null,
                ),
            ),
            error.problems,
        )
    }

    @Test
    fun `a non-object on the way to a root is one problem of the plugin at that root`() {
        val error = failure(builtIn(dataDir, AgentIndex::class, TelegramIndex::class), """{"channels": 1}""")

        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.INVALID_CONFIG,
                    "Invalid config at 'channels': must be an object",
                    "alexandrite-channel-telegram",
                ),
            ),
            error.problems,
        )
    }

    @Test
    fun `config below plugins that no plugin of the set reads is only reported`() {
        val plugins = builtIn(dataDir, AgentIndex::class, HelloIndex::class) + weather()
        val config = """{"plugins": {"hello": {"greeting": "hi"}, "weather": {}, "spy": {}}}"""

        val resolved = resolved(plugins, config)

        assertEquals(listOf("plugins.hello", "plugins.spy"), resolved.unknownPluginConfig)
    }

    @Test
    fun `config below plugins must be objects`() {
        val alone = failure(explicit(AgentIndex()), """{"plugins": []}""")
        val read = failure(explicit(weather()), """{"plugins": []}""")

        assertEquals(
            listOf("Invalid config at 'plugins': must be an object"),
            alone.problems.map { it.message },
        )
        assertEquals(
            listOf("weather" to "Invalid config at 'plugins': must be an object"),
            read.problems.map { it.plugin to it.message },
        )
    }

    // Sections.

    @Test
    fun `each enabled plugin decodes its sections from its own subtree`() {
        val config = """{"plugins": {"weather": {"enabled": true, "city": "Oslo", "cache": {"size": 5}}}}"""

        spec(explicit(weather()), dataDir, config).execute {
            assertEquals(
                mapOf("weather" to WeatherConfig("Oslo"), "cache" to CacheConfig(5)),
                services.get(key<Probe>()).values,
            )
        }
    }

    @Test
    fun `a section that does not decode fails with its message as is`() {
        val error = failure(explicit(weather()), """{"plugins": {"weather": {"cache": {"size": "s3cr3t"}}}}""")

        val problem = error.problems.single()
        assertEquals(RuntimeProblemKind.INVALID_CONFIG, problem.kind)
        assertEquals("weather", problem.plugin)
        assertContains(problem.message, "Invalid config at 'plugins.weather.cache': ")
        assertContains(error.message!!, problem.message)
        assertFalse("s3cr3t" in error.message!!)
    }

    @Test
    fun `a plugin root that no section decodes holds only enabled and the sections below it`() {
        val plugins =
            builtIn(dataDir, AgentIndex::class, ToolsIndex::class) + TestIndex("cache", sections = listOf(cacheSection))
        val config = """{"agent": {"modle": 1}, "tools": {"enabeld": false}, "plugins": {"cache": {"citty": 1}}}"""

        val error = failure(plugins, config)

        assertEquals(
            listOf(
                "alexandrite-agent" to "Invalid config at 'agent': unknown key 'modle'. Allowed keys: enabled.",
                "alexandrite-tools" to "Invalid config at 'tools': unknown key 'enabeld'. Allowed keys: enabled.",
                "cache" to "Invalid config at 'plugins.cache': unknown key 'citty'. Allowed keys: cache, enabled.",
            ),
            error.problems.map { it.plugin to it.message },
        )
    }

    @Test
    fun `a disabled plugin's sections are not decoded`() {
        val config = """{"plugins": {"weather": {"enabled": false, "cache": {"size": "big"}}}}"""

        assertEquals(listOf(DisabledPlugin("weather", ENABLED_FALSE)), resolved(explicit(weather()), config).disabled)
    }
}
