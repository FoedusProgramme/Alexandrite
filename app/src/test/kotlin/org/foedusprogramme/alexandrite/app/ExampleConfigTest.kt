package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.config.ConfigFile
import org.foedusprogramme.alexandrite.sdk.di.key
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleConfigTest {
    @TempDir
    lateinit var dataDir: Path

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
            listOf("--config", "$example", "--data-dir", "$dataDir"),
            emptyMap(),
            err = PrintStream(errors, true),
            execute = { spec, _ ->
                AlexandriteRuntime.run(spec) { settings = services.resolver().get(key<AppConfig>()) }
            },
        )

        assertEquals(0, code, errors.toString())
        val loaded = checkNotNull(settings)
        assertEquals(
            listOf<Any>(ZoneId.systemDefault(), 15L, 30L, emptyList<String>()),
            listOf(loaded.zoneId, loaded.shutdownGraceSeconds, loaded.startTimeoutSeconds, loaded.plugins),
        )
    }
}
