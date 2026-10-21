package org.unitedsoftware.alexandrite.tools.exec

import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandLineTest {
    private fun split(line: String, home: String? = "/h") = CommandLine.split(line, home)

    private fun problem(line: String): String = assertFailsWith<ToolRefusal>(line) { split(line) }.message

    @Test
    fun `words split at whitespace, and quotes and escapes keep them whole`() {
        assertEquals(listOf("printf", "%s|", "a b", "c d", "e f"), split("""printf '%s|' "a b" 'c d' e\ f"""))
        assertEquals(listOf("a\"b", "c\\d", "\$x", "`"), split(""""a\"b" "c\\d" "\${'$'}x" "\`""""))
        assertEquals(listOf(""), split("''"))
        assertEquals(listOf("git", "commit", "-m", "first\nsecond"), split("git commit -m 'first\nsecond'"))
        assertEquals(emptyList(), split(" \t "))
    }

    @Test
    fun `only a leading unquoted tilde expands to the home directory`() {
        assertEquals(listOf("echo", "/h", "/h/docs", "a~b", "~", "~root"), split("echo ~ ~/docs a~b '~' ~root"))
        assertEquals(listOf("ls", "~/x"), split("ls \"~/x\""))
        assertEquals(listOf("ls", "~/x"), split("ls ~/x", home = null))
    }

    @Test
    fun `variables and globs stay literal`() {
        assertEquals(listOf("echo", "\$HOME", "*.kt", "{a,b}"), split("echo \$HOME *.kt {a,b}"))
    }

    @Test
    fun `a quote never closed and a lone trailing backslash are problems`() {
        assertEquals("The command has a quote that is never closed.", problem("echo 'oops"))
        assertContains(problem("echo \"oops").orEmpty(), "never closed")
        assertContains(problem("trailing\\").orEmpty(), "lone backslash")
    }

    @Test
    fun `shell syntax outside quotes is a problem whose hint names cwd and sh -c`() {
        val tokens = mapOf(
            "ls | wc" to "'|'",
            "make || true" to "'||'",
            "cd src && make" to "'&&'",
            "ls;id" to "';'",
            "sleep 5 &" to "'&'",
            "echo x > out" to "'>'",
            "echo x >> out" to "'>>'",
            "make 2>&1" to "'>'",
            "wc < in" to "'<'",
            "echo \$(id)" to "'\$('",
            "echo `id`" to "'`'",
            "echo \"\$(id)\"" to "'\$('",
            "echo \"`id`\"" to "'`'",
            "ls\nid" to "a line break",
        )

        assertEquals(
            "The command holds '|', which is shell syntax, but no shell runs it: run one program and set " +
                "'cwd' instead of using cd; for pipes or redirects pass sh -c '…' explicitly.",
            problem("ls | wc"),
        )
        for ((line, token) in tokens) {
            assertContains(problem(line).orEmpty(), "holds $token, which is shell syntax", message = line)
        }
    }

    @Test
    fun `shell syntax inside quotes or escaped is a plain argument`() {
        assertEquals(
            listOf("sh", "-c", "ls | wc && echo \$(id) `id` > out"),
            split("sh -c 'ls | wc && echo \$(id) `id` > out'"),
        )
        assertEquals(listOf("grep", "a|b;c&d<e>f"), split("grep \"a|b;c&d<e>f\""))
        assertEquals(listOf("echo", "|", ";", "\$(x)", "`x`"), split("echo \\| \\; \\\$(x) \"\\`x\\`\""))
    }
}
