package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.key
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExampleConfigTest {
    @TempDir
    lateinit var directory: Path

    private val example = Path.of("src/dist/config/alexandrite.example.json")

    @Test
    fun `the example config holds every host setting`() {
        val tree = ConfigFile.read(example, emptyMap()).tree("app")
        val settings = AppConfig.serializer().descriptor

        assertEquals(List(settings.elementsCount, settings::getElementName).toSet(), tree?.keys)
    }

    @Test
    fun `the example config shows an MCP server run as a command and one reached over HTTP, both off`() {
        val servers = ConfigFile.read(example, emptyMap()).tree("mcp.servers").orEmpty()

        assertEquals(listOf("git", "docs"), servers.keys.toList())
        val (git, docs) = servers.values.map { it.jsonObject }
        assertEquals(listOf(true, false), listOf("command" in git, "url" in git))
        assertEquals(listOf(false, true), listOf("command" in docs, "url" in docs))
        assertTrue(servers.values.all { it.jsonObject["enabled"] == JsonPrimitive(false) })
    }

    @Test
    fun `the host runs with the example config`() {
        val errors = ByteArrayOutputStream()
        var settings: AppConfig? = null

        val code = run(
            listOf("--config", "$example", "--data-dir", "${directory.resolve("data")}"),
            emptyMap(),
            osName = "Linux",
            home = directory.resolve("home"),
            err = PrintStream(errors, true),
            execute = { spec, _ ->
                AlexandriteRuntime.run(spec) { settings = services.resolver().get(key<AppConfig>()) }
            },
        )

        assertEquals(0, code, errors.toString())
        assertTrue(Files.isDirectory(directory.resolve("data/cache")))
        assertFalse(Files.exists(directory.resolve("home")))
        val loaded = checkNotNull(settings)
        assertEquals(
            listOf<Any>(ZoneId.systemDefault(), LanguageTag("en"), 15L, 30L, emptyList<String>()),
            with(loaded) { listOf(zoneId, languageTag, shutdownGraceSeconds, startTimeoutSeconds, plugins) },
        )
    }
}
