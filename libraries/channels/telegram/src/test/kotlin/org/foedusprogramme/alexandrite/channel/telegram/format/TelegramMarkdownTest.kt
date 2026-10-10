package org.foedusprogramme.alexandrite.channel.telegram.format

import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TelegramMarkdownTest {
    // Golden files.

    @TestFactory
    fun `each Markdown file renders as its golden file says`(): List<DynamicTest> = GOLDENS.map { name ->
        DynamicTest.dynamicTest(name) {
            val actual = golden(TelegramMarkdown.render(resource("$name.md")))
            val expected = resource("$name.txt")
            if (actual != expected) {
                val file = Path.of("build", "golden", "$name.txt")
                Files.createDirectories(file.parent)
                Files.writeString(file, actual)
                assertEquals(expected, actual, "$name renders as $file holds")
            }
        }
    }

    @Test
    fun `entities nest properly and nothing nests in code or pre`() {
        for (name in GOLDENS) {
            val spans = TelegramMarkdown.render(resource("$name.md")).entities
            for (outer in spans) {
                for (inner in spans) {
                    if (outer === inner || inner.offset >= outer.end || outer.offset >= inner.end) continue
                    val nested = inner.offset >= outer.offset && inner.end <= outer.end
                    val contained = nested && outer != inner
                    if (!nested && !(outer.offset >= inner.offset && outer.end <= inner.end)) {
                        fail("$name: $outer and $inner overlap")
                    }
                    if (contained && outer.type in setOf("code", "pre")) fail("$name: $inner is inside $outer")
                    if (contained && outer.type == inner.type) fail("$name: $inner is inside $outer of its type")
                }
            }
        }
    }

    @Test
    fun `offsets and lengths count UTF-16 code units`() {
        val rendered = TelegramMarkdown.render("\uD83D\uDE00 **\uD835\uDCB3**")

        assertEquals("\uD83D\uDE00 \uD835\uDCB3", rendered.text)
        assertEquals(listOf(Span("bold", 3, 2)), rendered.entities)
    }

    // Clipping.

    @Test
    fun `a clipped text keeps its head and an ellipsis and clips its entities`() {
        val text = FormattedText("abcdefghij", listOf(Span("bold", 0, 3), Span("italic", 6, 4), Span("code", 2, 6)))

        val clipped = text.clipped(6)

        assertEquals(FormattedText("abcde…", listOf(Span("bold", 0, 3), Span("code", 2, 3))), clipped)
        assertEquals(text, text.clipped(10))
    }

    @Test
    fun `a clipped text never splits a surrogate pair`() {
        val text = FormattedText("abc\uD83D\uDE00def", listOf(Span("bold", 3, 2)))

        val clipped = text.clipped(5)

        assertEquals(FormattedText("abc…"), clipped)
        assertTrue(clipped.text.length <= 5)
    }

    // Markups.

    @Test
    fun `plain text keeps its markers and gets no entities`() {
        assertEquals(FormattedText("**not bold**"), formatted("**not bold**", Markup.PLAIN))
        assertEquals(FormattedText("**x**"), formatted("**x**", Markup.of("html")))
    }

    @Test
    fun `Markdown that shows nothing goes out as written`() {
        assertEquals(FormattedText("```\n```"), formatted("```\n```", Markup.MARKDOWN))
        assertEquals(FormattedText("bold", listOf(Span("bold", 0, 4))), formatted("**bold**", Markup.MARKDOWN))
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/markdown/$name")) { "No golden file $name" }.readText()

    private fun golden(text: FormattedText): String = buildString {
        append(text.text)
        append("\n~~~~ entities\n")
        for (span in text.entities) {
            append("${span.type} ${span.offset}+${span.length}")
            span.url?.let { append(" url=$it") }
            span.language?.let { append(" language=$it") }
            val covered = text.text.substring(span.offset, span.end)
            append(" \"${covered.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")}\"\n")
        }
    }

    private companion object {
        val GOLDENS = listOf(
            "emphasis",
            "nesting",
            "code",
            "unclosed_fence",
            "partial",
            "blocks",
            "quote",
            "table",
            "links",
            "escapes",
            "surrogates",
            "cjk",
            "html",
            "whitespace",
        )
    }
}
