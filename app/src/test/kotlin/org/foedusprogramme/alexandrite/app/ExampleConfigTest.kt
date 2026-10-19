package org.foedusprogramme.alexandrite.app

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotConfig
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.foedusprogramme.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.ConfigFile
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
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
    fun `the example config holds every host setting`() {
        val tree = ConfigFile.read(example, emptyMap()).tree("app")
        val settings = AppConfig.serializer().descriptor

        assertEquals(List(settings.elementsCount, settings::getElementName).toSet(), tree?.keys)
    }

    @Test
    fun `the example config's channel sections name only their own settings`() {
        val source = ConfigFile.read(example, emptyMap())

        assertKnownFields(
            checkNotNull(source.tree("channels.onebot.settings")),
            OneBotConfig.serializer().descriptor,
            "channels.onebot.settings",
        )
        assertKnownFields(
            checkNotNull(source.tree("channels.onebot.instances.main")),
            OneBotInstanceConfig.serializer().descriptor,
            "channels.onebot.instances.main",
        )
        assertKnownFields(
            checkNotNull(source.tree("channels.qqbot.instances.main")),
            QqBotInstanceConfig.serializer().descriptor,
            "channels.qqbot.instances.main",
        )
    }

    /** Asserts that [tree] names no field [descriptor] does not know. */
    private fun assertKnownFields(tree: JsonObject, descriptor: SerialDescriptor, path: String) {
        val known = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        assertEquals(emptySet(), tree.keys - known, "$path names a field its config does not have")
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
