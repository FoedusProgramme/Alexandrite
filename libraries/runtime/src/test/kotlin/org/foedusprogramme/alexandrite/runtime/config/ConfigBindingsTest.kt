package org.foedusprogramme.alexandrite.runtime.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.config.JsonConfigSource
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Container
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigBindingsTest {
    @Serializable
    data class WeatherConfig(val city: String = "Paris")

    @Serializable
    data class CacheConfig(val size: Int = 10)

    @Serializable
    data class DiskConfig(val directory: String = "cache")

    @Serializable
    data class AlertConfig(val level: Int = 1)

    @Serializable
    data class RequiredConfig(val command: String)

    enum class Mode { FAST, SAFE }

    @Serializable
    data class BotConfig(val token: Secret = Secret(""), val port: Int = 0, val mode: Mode = Mode.FAST)

    @Serializable
    data class CheckedConfig(val token: Secret, val port: Int) {
        init {
            check(port > 0) { "port of '${token.reveal()}' must be positive" }
        }
    }

    private inline fun <reified T : Any> spec(path: String, serializer: KSerializer<T>) =
        ConfigSectionSpec(key<T>(), path, serializer, "sample.${T::class.simpleName}")

    private val weather = spec("", WeatherConfig.serializer())
    private val cache = spec("cache", CacheConfig.serializer())
    private val disk = spec("cache.disk", DiskConfig.serializer())
    private val alerts = spec("alerts.severe", AlertConfig.serializer())

    private fun index(vararg sections: ConfigSectionSpec<*>) = object : PluginIndex {
        override val info = PluginInfo("weather", "Weather", "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "")
        override val configRoot: String = "plugins.weather"

        override fun bindings(): List<Binding<*>> = emptyList()

        override fun configSections(): List<ConfigSectionSpec<*>> = sections.toList()
    }

    private fun source(json: String) = JsonConfigSource(Json.parseToJsonElement(json).jsonObject)

    private fun bindings(json: String, vararg sections: ConfigSectionSpec<*>) =
        configBindings(index(*sections), source(json))

    private fun decoded(json: String, vararg sections: ConfigSectionSpec<*>): Container =
        Container.build(emptyList(), overrides = bindings(json, *sections))

    private fun failure(json: String, vararg sections: ConfigSectionSpec<*>) =
        assertFailsWith<ConfigException> { bindings(json, *sections) }

    // Decoding.

    @Test
    fun `each section decodes its subtree below the plugin root without its nested sections`() {
        val json = """
            {"plugins": {"weather": {
                "enabled": true,
                "city": "Oslo",
                "cache": {"size": 5, "disk": {"directory": "/var/cache"}},
                "alerts": {"severe": {"level": 3}}
            }}}
        """

        decoded(json, weather, cache, disk, alerts).use { container ->
            assertEquals(WeatherConfig("Oslo"), container.get(key<WeatherConfig>()))
            assertEquals(CacheConfig(5), container.get(key<CacheConfig>()))
            assertEquals(DiskConfig("/var/cache"), container.get(key<DiskConfig>()))
            assertEquals(AlertConfig(3), container.get(key<AlertConfig>()))
        }
    }

    @Test
    fun `missing sections decode with their defaults`() {
        for (json in listOf("{}", """{"plugins": {}}""", """{"plugins": {"weather": {"cache": {}}}}""")) {
            decoded(json, weather, cache, disk).use { container ->
                assertEquals(WeatherConfig(), container.get(key<WeatherConfig>()), json)
                assertEquals(CacheConfig(), container.get(key<CacheConfig>()), json)
                assertEquals(DiskConfig(), container.get(key<DiskConfig>()), json)
            }
        }
    }

    @Test
    fun `the bindings are unmanaged instances of the plugin under the section keys with their origins`() {
        val bindings = bindings("{}", weather, cache)

        assertEquals(listOf(key<WeatherConfig>(), key<CacheConfig>()), bindings.map { it.key })
        assertEquals(listOf("weather", "weather"), bindings.map { it.plugin })
        assertEquals(listOf("sample.WeatherConfig", "sample.CacheConfig"), bindings.map { it.origin })
        assertTrue(bindings.none { it.managed || it.multi })
    }

    @Test
    fun `a missing required field fails naming the path`() {
        val required = spec("exec", RequiredConfig.serializer())

        for (json in listOf("""{"plugins": {"weather": {"exec": {}}}}""", "{}")) {
            val error = failure(json, required)

            assertEquals("plugins.weather.exec", error.path, json)
            assertContains(error.message!!, "Invalid config at 'plugins.weather.exec': Field 'command' is required")
        }
    }

    // Keys.

    @Test
    fun `enabled is stripped from the plugin root only`() {
        decoded("""{"plugins": {"weather": {"enabled": false}}}""", weather).use { container ->
            assertEquals(WeatherConfig(), container.get(key<WeatherConfig>()))
        }

        val error = failure("""{"plugins": {"weather": {"cache": {"enabled": false}}}}""", weather, cache)
        assertEquals("plugins.weather.cache", error.path)
        assertContains(error.message!!, "unknown key 'enabled'")
    }

    @Test
    fun `a key that holds no section of the plugin is unknown to its parent`() {
        val error = failure("""{"plugins": {"weather": {"cache": {"size": 5}}}}""", weather)

        assertEquals("plugins.weather", error.path)
        assertContains(error.message!!, "Invalid config at 'plugins.weather': ")
        assertContains(error.message!!, "unknown key 'cache'")
    }

    @Test
    fun `an unknown key fails naming the full path`() {
        val error = failure("""{"plugins": {"weather": {"cache": {"disk": {"dir": "/tmp"}}}}}""", cache, disk)

        assertEquals("plugins.weather.cache.disk", error.path)
        assertContains(error.message!!, "Invalid config at 'plugins.weather.cache.disk': ")
        assertContains(error.message!!, "unknown key 'dir'")
    }

    @Test
    fun `a root that no section decodes may hold only enabled and the sections below it`() {
        val valid = """{"plugins": {"weather": {"enabled": true, "cache": {}, "alerts": {"severe": {}}}}}"""
        decoded(valid, cache, alerts).close()

        val error = failure("""{"plugins": {"weather": {"enabeld": false, "cahce": {}}}}""", cache, alerts)

        assertEquals("plugins.weather", error.path)
        assertEquals(
            "Invalid config at 'plugins.weather': unknown keys 'cahce', 'enabeld'. " +
                "Allowed keys: alerts, cache, enabled.",
            error.message,
        )
    }

    @Test
    fun `an object on the way to a section may hold only the sections below it`() {
        val error = failure("""{"plugins": {"weather": {"alerts": {"severe": {}, "mild": {}}}}}""", weather, alerts)

        assertEquals("plugins.weather.alerts", error.path)
        assertEquals(
            "Invalid config at 'plugins.weather.alerts': unknown key 'mild'. Allowed keys: severe.",
            error.message,
        )
    }

    @Test
    fun `a plugin without sections accepts only enabled at its root`() {
        assertEquals(emptyList(), bindings("""{"plugins": {"weather": {"enabled": true}}}"""))

        val error = failure("""{"plugins": {"weather": {"modle": 1}}}""")

        assertEquals("Invalid config at 'plugins.weather': unknown key 'modle'. Allowed keys: enabled.", error.message)
    }

    @Test
    fun `a non-object on the way to a section fails naming the full path`() {
        val cases = mapOf(
            """{"plugins": {"weather": {"cache": "big"}}}""" to ("plugins.weather.cache" to "plugins.weather.cache"),
            """{"plugins": {"weather": ["sunny"]}}""" to ("plugins.weather" to "plugins.weather"),
            """{"plugins": "weather"}""" to ("plugins.weather" to "plugins"),
        )

        for ((json, expected) in cases) {
            val (path, offender) = expected
            val error = failure(json, cache)
            assertEquals(path, error.path, json)
            assertEquals("Invalid config at '$path': '$offender' is not an object", error.message, json)
        }
    }

    // Redaction.

    @Test
    fun `a secret never appears in an error message`() {
        val bot = spec("bot", BotConfig.serializer())
        val configs = listOf(
            """{"token": "s3cr3t", "tokn": "s3cr3t"}""",
            """{"token": "s3cr3t", "port": "s3cr3t"}""",
            """{"mode": "s3cr3t"}""",
            """{"extra": {"nested": ["s3cr3t"]}}""",
        )

        for (config in configs) {
            val error = failure("""{"plugins": {"weather": {"bot": $config}}}""", bot)
            val messages = generateSequence<Throwable>(error) { it.cause }.map { it.message.orEmpty() }
            assertEquals("plugins.weather.bot", error.path, config)
            assertTrue(messages.none { "s3cr3t" in it }, error.message)
        }
    }

    @Test
    fun `a section that fails its own checks fails naming the path without quoting the config`() {
        val checked = spec("bot", CheckedConfig.serializer())

        val error = failure("""{"plugins": {"weather": {"bot": {"token": "s3cr3t", "port": -1}}}}""", checked)

        assertEquals("plugins.weather.bot", error.path)
        assertEquals("Invalid config at 'plugins.weather.bot': port of '***' must be positive", error.message)
        assertFalse(generateSequence<Throwable>(error) { it.cause }.any { "s3cr3t" in it.message.orEmpty() })
    }
}
