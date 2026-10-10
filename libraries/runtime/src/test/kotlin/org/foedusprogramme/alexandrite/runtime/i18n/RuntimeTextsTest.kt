package org.foedusprogramme.alexandrite.runtime.i18n

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.logged
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RuntimeTextsTest {
    @TempDir
    lateinit var directory: Path

    private val problems = mutableListOf<String>()
    private val classLoader = javaClass.classLoader

    private val texts = RuntimeTexts(mapOf("alpha" to classLoader, "beta" to classLoader), LanguageTag("en"), ::record)

    private fun record(plugin: String, problem: String) {
        problems += "$plugin $problem"
    }

    @Test
    fun `an installed catalog loses only the texts whose placeholders differ from the plugin's, with a warning each`() {
        texts.install(
            TextCatalog.builder()
                .text("alpha", LanguageTag("zh-CN"), "greeting", "嗨，{name}！")
                .text("alpha", LanguageTag("de"), "greeting", "Hallo, {user}!")
                .text("alpha", LanguageTag("fr"), "greeting", "Salut !")
                .text("alpha", LanguageTag("de"), "farewell", "Tschüss")
                .text("alpha", LanguageTag("fr"), "farewell", "Salut")
                .text("gamma", LanguageTag("en"), "greeting", "Hey")
                .build(),
        )
        val alpha = texts.of("alpha")

        assertEquals(
            listOf(
                "alpha ignores the host's text 'greeting' (de): its placeholders [{user}] are not those of " +
                    "en.properties, [{name}]",
                "alpha ignores the host's text 'greeting' (fr): its placeholders [] are not those of en.properties, " +
                    "[{name}]",
                "alpha does not define the host's text 'farewell' in its en.properties",
                "gamma is not in the plugin set; the host's texts of it go unused",
            ),
            problems,
        )
        assertEquals("嗨，Ada！", alpha.text("greeting", LanguageTag("zh-CN"), "name" to "Ada"))
        assertEquals("Hello, Ada!", alpha.text("greeting", LanguageTag("de"), "name" to "Ada"))
        assertEquals("Tschüss", alpha.text("farewell", LanguageTag("de")))
    }

    @Test
    fun `each distinct problem is told once, however often catalogs are installed`() {
        val bad = TextCatalog.builder().text("alpha", LanguageTag("de"), "greeting", "Hallo!").build()

        texts.install(bad)
        texts.install(bad.toBuilder().text("beta", LanguageTag("de"), "greeting", "Hallo von beta").build())
        texts.install(bad)
        texts.of("alpha").text("missing", null)
        texts.of("alpha").text("missing", LanguageTag("de"))

        assertEquals(
            listOf(
                "alpha ignores the host's text 'greeting' (de): its placeholders [] are not those of en.properties, " +
                    "[{name}]",
                "alpha has no text 'missing' in en; the key is shown instead",
            ),
            problems,
        )
    }

    @Test
    fun `an installed catalog replaces the one before for every plugin`() {
        val alpha = texts.of("alpha")
        val beta = texts.of("beta")
        texts.install(TextCatalog.builder().text("beta", LanguageTag("en"), "greeting", "Beta, first").build())

        assertEquals("Beta, first", beta.text("greeting", null))

        texts.install(TextCatalog.builder().text("alpha", LanguageTag("en"), "greeting", "Hey {name}").build())

        assertEquals("Hey Ada", alpha.text("greeting", null, "name" to "Ada"))
        assertEquals("Hi from beta", beta.text("greeting", null))
        assertEquals(emptyList(), problems)
    }

    // The runtime.

    @Test
    fun `the runtime binds each plugin its texts over the host's catalog, which it follows while it runs`() {
        val catalog = MutableStateFlow(
            TextCatalog.builder()
                .text("beta", LanguageTag("zh-CN"), "greeting", "来自 beta 的问候")
                .text("gamma", LanguageTag("en"), "greeting", "Hey")
                .build(),
        )
        val config = RuntimeConfig.builder(directory).language(LanguageTag("zh-CN")).texts(catalog).name("test").build()
        val spec = RuntimeSpec.builder(config, explicit(TestIndex("alpha"), TestIndex("beta"))).build()
        val seen = mutableListOf<String>()

        val lines = logged {
            spec.execute {
                val alpha = services.resolver().get(key<Texts>("alpha"))
                val beta = services.resolver().get(key<Texts>("beta"))
                seen += alpha.text("greeting", null, "name" to "Ada")
                seen += alpha.text("greeting", LanguageTag("en-GB"), "name" to "Ada")
                seen += beta.text("greeting", null)
                seen += beta.text("missing", null)
                catalog.value = TextCatalog.builder().text("alpha", LanguageTag("zh"), "greeting", "嗨，{name}！").build()
                withTimeout(10.seconds) {
                    while (alpha.text("greeting", LanguageTag("zh-Hant"), "name" to "Ada") != "嗨，Ada！") {
                        delay(10.milliseconds)
                    }
                }
                seen += beta.text("greeting", null)
            }
        }

        assertEquals(listOf("你好，Ada！", "Hello, Ada!", "来自 beta 的问候", "missing", "Hi from beta"), seen)
        assertContains(lines, "WARN test: plugin gamma is not in the plugin set; the host's texts of it go unused")
        assertContains(lines, "WARN test: plugin beta has no text 'missing' in zh-CN, zh, en; the key is shown instead")
    }
}
