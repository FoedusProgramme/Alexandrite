package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.ConfigFile
import org.foedusprogramme.alexandrite.sdk.di.key
import org.junit.jupiter.api.io.TempDir
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
    fun `the example config holds the default host settings`() {
        val tree = ConfigFile.read(example, emptyMap()).tree("app")

        assertEquals(setOf("zone", "shutdownGraceSeconds", "startTimeoutSeconds", "plugins"), tree?.keys)
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
            listOf<Any>(ZoneId.systemDefault(), 15L, 30L, emptyList<String>()),
            listOf(loaded.zoneId, loaded.shutdownGraceSeconds, loaded.startTimeoutSeconds, loaded.plugins),
        )
    }
}
