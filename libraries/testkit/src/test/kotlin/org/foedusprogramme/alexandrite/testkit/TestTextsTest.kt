package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class TestTextsTest {
    @TempDir
    lateinit var directory: Path

    private val english = LanguageTag("en")
    private val chinese = LanguageTag("zh-CN")

    private class Greeter(private val texts: Texts) {
        fun greet(language: LanguageTag?): String = texts.text("greeting", language, "name" to "Ada")
    }

    private val greeter = TestIndex(
        "greeter",
        listOf(
            binding(
                key<Greeter>(),
                "greeter",
                "Greeter",
                dependencies = listOf(Dependency(key<Texts>("greeter"), DependencyKind.INSTANCE, "texts")),
            ) { r -> Greeter(r.get(key<Texts>("greeter"))) },
        ),
    )

    /** A class loader over [roots], each a map of resource names to their texts. */
    private fun classLoader(vararg roots: Map<String, String>): URLClassLoader {
        val urls = roots.mapIndexed { number, files ->
            val root = directory.resolve("root$number")
            for ((name, text) in files) {
                Files.createDirectories(root.resolve(name).parent)
                Files.writeString(root.resolve(name), text)
            }
            root.toUri().toURL()
        }
        return URLClassLoader(urls.toTypedArray(), null)
    }

    // The harness and the standalone texts.

    @Test
    fun `a harness run binds the plugin's texts from its resources in the host's language over the host's catalog`() {
        val greetings = mutableListOf<String>()
        val catalog = TextCatalog.builder().text("greeter", LanguageTag("en-GB"), "greeting", "Hiya, {name}!").build()

        blocking {
            PluginHarness.builder(greeter).build().run { greetings += get<Greeter>().greet(null) }
            PluginHarness.builder(greeter).language(chinese).texts(catalog).build().run {
                val greeter = get<Greeter>()
                greetings += greeter.greet(null)
                greetings += greeter.greet(LanguageTag("en-GB"))
                greetings += greeter.greet(LanguageTag("en-US"))
            }
        }

        assertEquals(listOf("Hello, Ada!", "你好，Ada！", "Hiya, Ada!", "Hello, Ada!"), greetings)
    }

    @Test
    fun `a harness run follows the catalog it is given as a flow`() {
        val catalog = MutableStateFlow(TextCatalog.EMPTY)
        lateinit var greeting: String

        blocking {
            PluginHarness.builder(greeter).texts(catalog).build().run {
                val greeter = get<Greeter>()
                catalog.value = TextCatalog.builder().text("greeter", english, "greeting", "Hey, {name}!").build()
                while (greeter.greet(null) != "Hey, Ada!") delay(10.milliseconds)
                greeting = greeter.greet(null)
            }
        }

        assertEquals("Hey, Ada!", greeting)
    }

    @Test
    fun `test texts read a plugin's resources over a catalog without a runtime`() {
        val catalog = TextCatalog.builder().text("greeter", english, "farewell", "Bye.").build()

        val texts = testTexts("greeter", chinese, catalog)

        assertEquals("你好，Ada！", texts.text("greeting", null, "name" to "Ada"))
        assertEquals("Hello, Ada!", texts.text("greeting", english, "name" to "Ada"))
        assertEquals("Bye.", texts.text("farewell", english))
        assertEquals("missing", testTexts().text("missing", null))
    }

    // The completeness check.

    @Test
    fun `complete texts pass the check, which returns their languages`() {
        assertEquals(listOf(english, chinese), assertTextsComplete("greeter"))
    }

    @Test
    fun `the check lists missing and extra keys, other placeholders and misnamed files`() {
        val error = assertFailsWith<AssertionError> { assertTextsComplete("broken-texts") }

        assertEquals(
            """
            The texts of plugin 'broken-texts' in alexandrite/i18n/broken-texts are incomplete:
            - de.properties lacks 'count'
            - fr.properties has 'extra', which en.properties lacks
            - zh-CN.properties has the placeholders [] in 'farewell', en.properties [{name}]
            - zh-CN.properties has the placeholders [{user}] in 'greeting', en.properties [{name}]
            - zh_TW.properties is not named by a canonical language tag (rename it zh-TW.properties)
            """.trimIndent(),
            error.message,
        )
    }

    @Test
    fun `the check reads the texts of a jar`() {
        val jar = directory.resolve("texts.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            val entries = mapOf(
                "alexandrite/i18n/jarred/en.properties" to "a=A\nb=B {n}\n",
                "alexandrite/i18n/jarred/zh-CN.properties" to "a=甲\n",
                "alexandrite/i18n/jarred/old/de.properties" to "a=A\n",
                "alexandrite/i18n/other/fr.properties" to "x=X\n",
                "fr.properties" to "x=X\n",
            )
            for ((name, text) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }

        val error = URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { classLoader ->
            assertFailsWith<AssertionError> { assertTextsComplete("jarred", classLoader) }
        }

        assertEquals(
            "The texts of plugin 'jarred' in alexandrite/i18n/jarred are incomplete:\n- zh-CN.properties lacks 'b'",
            error.message,
        )
    }

    @Test
    fun `the check fails without en properties, on a text file twice on the class path and on an unreadable file`() {
        val none = classLoader(mapOf("alexandrite/i18n/other/en.properties" to "a=A\n"))
        val twice = classLoader(
            mapOf("alexandrite/i18n/twice/en.properties" to "a=A\n", "alexandrite/i18n/twice/de.properties" to "a=A\n"),
            mapOf("alexandrite/i18n/twice/de.properties" to "a=B\n"),
        )
        val bad = classLoader(
            mapOf(
                "alexandrite/i18n/bad/en.properties" to "a=A\n",
                "alexandrite/i18n/bad/de.properties" to "a=\\uZZZZ\n",
            ),
        )

        val missing = assertFailsWith<AssertionError> { assertTextsComplete("absent", none) }
        val duplicated = assertFailsWith<AssertionError> { assertTextsComplete("twice", twice) }
        val unreadable = assertFailsWith<AssertionError> { assertTextsComplete("bad", bad) }

        assertEquals(
            "Plugin 'absent' has no texts: alexandrite/i18n/absent/en.properties is not on the class path.",
            missing.message,
        )
        assertContains(duplicated.message.orEmpty(), "- de.properties is on the class path 2 times: ")
        assertContains(unreadable.message.orEmpty(), "- de.properties cannot be read: java.io.IOException: Malformed")
    }

    @Test
    fun `a catalog passes the check when each of its languages holds the plugin's keys with their placeholders`() {
        val complete = TextCatalog.builder()
            .texts("greeter", chinese, mapOf("greeting" to "嗨，{name}！", "farewell" to "拜拜。"))
            .build()
        val incomplete = complete.toBuilder()
            .texts("greeter", LanguageTag("de"), mapOf("greeting" to "Hallo!", "extra" to "Mehr."))
            .build()

        val error = assertFailsWith<AssertionError> { assertTextsComplete("greeter", incomplete) }

        assertEquals(listOf(chinese), assertTextsComplete("greeter", complete))
        assertEquals(
            """
            The catalog's texts of plugin 'greeter' are incomplete:
            - de lacks 'farewell'
            - de has 'extra', which en.properties lacks
            - de has the placeholders [] in 'greeting', en.properties [{name}]
            """.trimIndent(),
            error.message,
        )
        assertFailsWith<AssertionError> { assertTextsComplete("greeter", TextCatalog.EMPTY) }
    }
}
