package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex
import org.foedusprogramme.alexandrite.sdk.di.key
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    enum class Mode { FAST, SAFE }

    @Serializable
    data class BotConfig(val token: Secret = Secret(""), val port: Int = 0, val mode: Mode = Mode.FAST)

    private inline fun <reified T : Any> spec(path: String, serializer: KSerializer<T>) =
        ConfigSectionSpec(key<T>(), path, serializer, "${T::class.simpleName} (module weather)")

    private val weather = spec("", WeatherConfig.serializer())
    private val cache = spec("cache", CacheConfig.serializer())
    private val disk = spec("cache.disk", DiskConfig.serializer())
    private val alerts = spec("alerts.severe", AlertConfig.serializer())

    private fun index(root: String?, vararg sections: ConfigSectionSpec<*>) = object : ModuleIndex {
        override val module: String = "weather"
        override val configRoot: String? = root

        override fun bindings(): List<Binding<*>> = emptyList()

        override fun configSections(): List<ConfigSectionSpec<*>> = sections.toList()
    }

    private fun source(json: String) = JsonConfigSource(Json.parseToJsonElement(json).jsonObject)

    private fun bindings(json: String, vararg sections: ConfigSectionSpec<*>) =
        configBindings(index("plugins.weather", *sections), source(json))

    private fun decoded(json: String, vararg sections: ConfigSectionSpec<*>): Container =
        Container.build(emptyList(), overrides = bindings(json, *sections))

    private fun failure(json: String, vararg sections: ConfigSectionSpec<*>) =
        assertFailsWith<ConfigException> { bindings(json, *sections) }

    @Test
    fun `each section decodes its subtree below the module root without its nested sections`() {
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
    fun `the bindings are unmanaged instances of the module under the section keys with their origins`() {
        val bindings = bindings("{}", weather, cache)

        assertEquals(listOf(key<WeatherConfig>(), key<CacheConfig>()), bindings.map { it.key })
        assertEquals(listOf("weather", "weather"), bindings.map { it.module })
        assertEquals(
            listOf("WeatherConfig (module weather)", "CacheConfig (module weather)"),
            bindings.map { it.origin },
        )
        assertTrue(bindings.none { it.managed || it.multi })
    }

    @Test
    fun `enabled is stripped from the module root only`() {
        decoded("""{"plugins": {"weather": {"enabled": false}}}""", weather).use { container ->
            assertEquals(WeatherConfig(), container.get(key<WeatherConfig>()))
        }

        val error = failure("""{"plugins": {"weather": {"cache": {"enabled": false}}}}""", weather, cache)
        assertEquals("plugins.weather.cache", error.path)
        assertContains(error.message!!, "unknown key 'enabled'")
    }

    @Test
    fun `a key that holds no section of the module is unknown to its parent`() {
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
    fun `a non-object at a section fails naming the full path`() {
        val cases = mapOf(
            """{"plugins": {"weather": {"cache": "big"}}}""" to ("plugins.weather.cache" to "plugins.weather.cache"),
            """{"plugins": {"weather": ["sunny"]}}""" to ("plugins.weather.cache" to "plugins.weather"),
            """{"plugins": "weather"}""" to ("plugins.weather.cache" to "plugins"),
        )

        for ((json, expected) in cases) {
            val (path, offender) = expected
            val error = failure(json, cache)
            assertEquals(path, error.path, json)
            assertEquals("Invalid config at '$path': '$offender' is not an object", error.message, json)
        }
    }

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
    fun `an index with sections but no config root fails`() {
        val error = assertFailsWith<ConfigException> { configBindings(index(null, weather, cache), source("{}")) }

        assertNull(error.path)
        assertContains(error.message!!, "Module 'weather' has no config root")
        assertContains(error.message!!, "WeatherConfig (module weather), CacheConfig (module weather)")
    }

    @Test
    fun `an index without sections needs no config root`() {
        assertEquals(emptyList(), configBindings(index(null), source("""{"plugins": "weather"}""")))
    }
}
