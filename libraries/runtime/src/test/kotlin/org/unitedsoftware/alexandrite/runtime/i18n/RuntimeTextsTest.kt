package org.unitedsoftware.alexandrite.runtime.i18n

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.RuntimeConfig
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.TestIndex
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.runtime.execute
import org.unitedsoftware.alexandrite.runtime.explicit
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
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

        assertEquals(4, problems.size, "$problems")
        assertContains(problems[0], "'greeting' (de)")
        assertContains(problems[1], "'greeting' (fr)")
        assertContains(problems[2], "'farewell'")
        assertContains(problems[3], "gamma")
        assertEquals("嗨，Ada！", alpha.text("greeting", LanguageTag("zh-CN"), "name" to "Ada"))
        assertEquals("Hello, Ada!", alpha.text("greeting", LanguageTag("de"), "name" to "Ada"))
        assertEquals("Tschüss", alpha.text("farewell", LanguageTag("de")))
    }

    @Test
    fun `a blank text of the host's catalog is ignored with a warning, so the plugin's own text stands`() {
        texts.install(
            TextCatalog.builder()
                .text("alpha", LanguageTag("zh-CN"), "greeting", " ")
                .text("alpha", LanguageTag("en"), "greeting", "")
                .text("alpha", LanguageTag("de"), "greeting", "\t")
                .build(),
        )
        val alpha = texts.of("alpha")

        assertEquals("你好，Ada！", alpha.text("greeting", LanguageTag("zh-CN"), "name" to "Ada"))
        assertEquals("Hello, Ada!", alpha.text("greeting", LanguageTag("de"), "name" to "Ada"))
        assertEquals(3, problems.size, "$problems")
        listOf("de", "en", "zh-CN").zip(problems).forEach { (tag, problem) ->
            assertContains(problem, "'greeting' ($tag)")
        }
    }

    @Test
    fun `each distinct problem is told once, however often catalogs are installed`() {
        val bad = TextCatalog.builder().text("alpha", LanguageTag("de"), "greeting", "Hallo!").build()

        texts.install(bad)
        texts.install(bad.toBuilder().text("beta", LanguageTag("de"), "greeting", "Hallo von beta").build())
        texts.install(bad)
        texts.of("alpha").text("missing", null)
        texts.of("alpha").text("missing", LanguageTag("de"))

        assertEquals(2, problems.size, "$problems")
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
        val config = RuntimeConfig.builder(directory).language(LanguageTag("zh-CN")).name("test").build()
        val spec = RuntimeSpec.builder(config, explicit(TestIndex("alpha"), TestIndex("beta")))
            .texts(catalog)
            .build()
        val seen = mutableListOf<String>()

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

        assertEquals(listOf("你好，Ada！", "Hello, Ada!", "来自 beta 的问候", "missing", "Hi from beta"), seen)
    }
}
