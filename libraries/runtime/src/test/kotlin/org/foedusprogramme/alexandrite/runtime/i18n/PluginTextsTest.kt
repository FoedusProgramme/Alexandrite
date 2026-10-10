package org.foedusprogramme.alexandrite.runtime.i18n

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.junit.jupiter.api.io.TempDir
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginTextsTest {
    @TempDir
    lateinit var directory: Path

    private val problems: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var catalog = TextCatalog.EMPTY

    private val loader: ClassLoader by lazy {
        object : URLClassLoader(arrayOf(directory.toUri().toURL()), null) {
            override fun getResource(name: String): URL? = super.getResource(name).also { lookups += name }
        }
    }

    private fun file(plugin: String, tag: String): Path =
        directory.resolve(textsPath(plugin, tag)).also { Files.createDirectories(it.parent) }

    private fun write(tag: String, text: String, plugin: String = "notes") {
        Files.writeString(file(plugin, tag), text)
    }

    private fun texts(plugin: String = "notes", host: String = "en"): Texts =
        PluginTexts(plugin, loader, LanguageTag(host), { owner, problem -> problems += "$owner $problem" }) { catalog }

    // Fallback.

    @Test
    fun `the fallback runs from the requested tag through its shorter tags to the host's language, then en`() {
        val chinese = LanguageTag("zh-Hant-TW")
        val swiss = LanguageTag("de-CH")

        assertEquals(listOf("zh-Hant-TW", "zh-Hant", "zh", "de-CH", "de", "en"), fallbacks(chinese, swiss))
        assertEquals(listOf("de-CH", "de", "en"), fallbacks(null, swiss))
        assertEquals(listOf("en-GB", "en", "zh-CN", "zh"), fallbacks(LanguageTag("en-GB"), LanguageTag("zh-CN")))
        assertEquals(listOf("en"), fallbacks(null, LanguageTag("en")))
    }

    @Test
    fun `each text comes from the first language of the fallback that holds it`() {
        write("en", "a=A en\nb=B en\nc=C en\nd=D en\n")
        write("zh", "a=A zh\nb=B zh\n")
        write("zh-Hant", "a=A zh-Hant\n")
        write("de", "c=C de\n")
        val texts = texts(host = "de-CH")

        assertEquals(
            listOf("A zh-Hant", "B zh", "C de", "D en"),
            listOf("a", "b", "c", "d").map { texts.text(it, LanguageTag("zh-Hant-TW")) },
        )
        assertEquals(listOf("A en", "C de"), listOf(texts.text("a", null), texts.text("c", null)))
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `a key that no language holds is shown as it is and warned about once`() {
        write("en", "a=A\n")
        val texts = texts()

        assertEquals("missing.key", texts.text("missing.key", LanguageTag("fr")))
        assertEquals("missing.key", texts.text("missing.key", null))

        assertEquals(listOf("notes has no text 'missing.key' in fr, en; the key is shown instead"), problems)
    }

    // Placeholders.

    @Test
    fun `a named placeholder takes its argument and every other brace is literal`() {
        write(
            "en",
            """
            saved=Saved note #{id} at {time_1}.
            literal={} { id } {1x} {-} {{id}} {id
            twice={id} and {id}
            """.trimIndent(),
        )
        val texts = texts()

        assertEquals("Saved note #7 at noon.", texts.text("saved", null, "id" to 7, "time_1" to "noon", "unused" to 1))
        assertEquals("{} { id } {1x} {-} {7} {id", texts.text("literal", null, "id" to 7))
        assertEquals("{a} and {a}", texts.text("twice", null, "id" to "{a}", "a" to "x"))
        assertEquals("null and null", texts.text("twice", null, "id" to null))
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `a placeholder without an argument stays as written and is warned about once`() {
        write("en", "saved=Saved note #{id}.\n")
        val texts = texts()

        assertEquals("Saved note #{id}.", texts.text("saved", null))
        assertEquals("Saved note #{id}.", texts.text("saved", null, "other" to 1))

        assertEquals(
            listOf("notes got no argument {id} for its text 'saved' (en), which shows it as written"),
            problems,
        )
    }

    // Files.

    @Test
    fun `files are read as UTF-8`() {
        write("en", "saved=Saved note #{id}.\n")
        write("zh-CN", "saved=已保存笔记 #{id}。\nescaped=\\u4F60好\n")
        val texts = texts()

        assertEquals("已保存笔记 #7。", texts.text("saved", LanguageTag("zh-CN"), "id" to 7))
        assertEquals("你好", texts.text("escaped", LanguageTag("zh-CN")))
        val read = readTexts(file("notes", "zh-CN").toUri().toURL())
        assertEquals(mapOf("saved" to "已保存笔记 #{id}。", "escaped" to "你好"), read)
    }

    @Test
    fun `a file that cannot be read counts as absent and is warned about once`() {
        write("en", "saved=Saved\n")
        Files.write(file("notes", "de"), "saved=Für dich gespeichert\n".toByteArray(Charsets.ISO_8859_1))
        val texts = texts()

        assertEquals("Saved", texts.text("saved", LanguageTag("de")))
        assertEquals("Saved", texts.text("saved", LanguageTag("de")))

        val problem = problems.single()
        assertTrue(problem.startsWith("notes cannot read alexandrite/i18n/notes/de.properties, whose texts "), problem)
    }

    @Test
    fun `each plugin reads only its own texts`() {
        write("en", "greeting=Hello from alpha\nown=Alpha's own\n", plugin = "alpha")
        write("en", "greeting=Hello from beta\n", plugin = "beta")

        assertEquals("Hello from alpha", texts("alpha").text("greeting", null))
        assertEquals("Hello from beta", texts("beta").text("greeting", null))
        assertEquals("own", texts("beta").text("own", null))
    }

    @Test
    fun `each file is read on first use, once`() {
        write("en", "a=A\n")
        val texts = texts(host = "de")

        assertEquals(emptyList(), lookups)

        repeat(3) { assertEquals("A", texts.text("a", LanguageTag("fr"))) }
        texts.text("a", LanguageTag("en"))

        assertEquals(listOf("fr", "de", "en").map { textsPath("notes", it) }, lookups)
    }

    @Test
    fun `concurrent first uses read each file once`() {
        write("en", "a=A\n")
        val texts = texts()

        val results = runBlocking(Dispatchers.Default) {
            (1..32).map { async { texts.text("a", LanguageTag("fr")) } }.awaitAll()
        }

        assertEquals(List(32) { "A" }, results)
        assertEquals(listOf("en", "fr").map { textsPath("notes", it) }, lookups.sorted())
    }

    // The host's catalog.

    @Test
    fun `in each language of the fallback the host's catalog comes before the plugin's own texts`() {
        write("en", "a=A en\nb=B en\n")
        write("zh-CN", "a=A zh-CN\n")
        catalog = TextCatalog.builder()
            .text("notes", LanguageTag("en"), "a", "A host en")
            .text("notes", LanguageTag("zh"), "a", "A host zh")
            .text("notes", LanguageTag("zh"), "b", "B host zh")
            .text("other", LanguageTag("en"), "b", "B other")
            .build()
        val texts = texts()

        assertEquals("A host en", texts.text("a", null))
        assertEquals("A zh-CN", texts.text("a", LanguageTag("zh-CN")))
        assertEquals("A host zh", texts.text("a", LanguageTag("zh-Hant")))
        assertEquals("B host zh", texts.text("b", LanguageTag("zh-CN")))
        assertEquals("B en", texts.text("b", LanguageTag("fr")))
    }

    @Test
    fun `a text comes from the catalog in place on its lookup`() {
        write("en", "a=A en\n")
        val texts = texts()

        assertEquals("A en", texts.text("a", null))

        catalog = TextCatalog.builder().text("notes", LanguageTag("en"), "a", "A host").build()

        assertEquals("A host", texts.text("a", null))
    }
}
