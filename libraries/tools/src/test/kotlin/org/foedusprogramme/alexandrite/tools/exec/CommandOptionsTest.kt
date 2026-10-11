package org.foedusprogramme.alexandrite.tools.exec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandOptionsTest {
    private fun parse(vararg argv: String) = CommandOptions.parse(argv.toList())

    @Test
    fun `plain flags and operands`() {
        val parsed = parse("ls", "-la", "/tmp")
        assertFalse(parsed.isRecursive)
        assertFalse(parsed.readsFromFile)
        assertFalse(parsed.hasUnrecognized)
        assertEquals(listOf("/tmp"), parsed.operands)
    }

    @Test
    fun `grouped short flags expand`() {
        assertTrue(parse("grep", "-Hr", "foo", "src").isRecursive)
        assertTrue(parse("grep", "-rn", "foo", "src").isRecursive)
        assertTrue(parse("grep", "-nR", "foo", "src").isRecursive)
        assertTrue(parse("ls", "-laR").isRecursive)
        assertEquals(listOf("foo", "src"), parse("grep", "-Hr", "foo", "src").operands)
    }

    @Test
    fun `long options resolve unambiguous GNU prefixes`() {
        assertTrue(parse("grep", "--recursive", "foo", "src").isRecursive)
        assertTrue(parse("grep", "--recurs", "foo", "src").isRecursive)
        assertTrue(parse("grep", "--dereference-recursive", "foo").isRecursive)
        assertTrue(parse("grep", "--deref", "foo").isRecursive)
        assertTrue(parse("ls", "--recursive").isRecursive)
        assertFalse(parse("grep", "--ignore-case", "foo", "src").isRecursive)
        assertFalse(parse("grep", "--ignore", "foo", "src").hasUnrecognized)
    }

    @Test
    fun `ambiguous or unknown long options fail closed`() {
        val ambiguous = parse("grep", "--re", "foo", "src")
        assertTrue(ambiguous.hasUnrecognized)
        assertTrue(ambiguous.isRecursive)
        assertTrue(ambiguous.readsFromFile)
        assertTrue(parse("grep", "--bogus", "foo").isRecursive)
        assertTrue(parse("grep", "--count=3", "foo").hasUnrecognized)
    }

    @Test
    fun `unknown short options fail closed`() {
        val parsed = parse("grep", "-Q", "foo", "file")
        assertTrue(parsed.hasUnrecognized)
        assertTrue(parsed.isRecursive)
        assertTrue(parsed.readsFromFile)
    }

    @Test
    fun `directories recurse in every spelling`() {
        assertTrue(parse("grep", "-d", "recurse", "foo", "src").isRecursive)
        assertTrue(parse("grep", "-drecurse", "foo", "src").isRecursive)
        assertTrue(parse("grep", "--directories=recurse", "foo", "src").isRecursive)
        assertTrue(parse("grep", "--directories", "recurse", "foo", "src").isRecursive)
        assertTrue(parse("grep", "--dir=recurse", "foo", "src").isRecursive)
        assertTrue(parse("grep", "--dir=rec", "foo", "src").isRecursive)
        assertFalse(parse("grep", "-d", "skip", "foo", "src").isRecursive)
        assertFalse(parse("grep", "--directories=read", "foo", "src").isRecursive)
        assertEquals(listOf("foo", "src"), parse("grep", "-d", "recurse", "foo", "src").operands)
    }

    @Test
    fun `file-reading options in every spelling`() {
        val glued = parse("grep", "-f/etc/passwd", "file")
        assertTrue(glued.readsFromFile)
        assertEquals(listOf("/etc/passwd"), glued.optionValues)
        assertEquals(listOf("file"), glued.operands)
        assertTrue(parse("grep", "-f", "/etc/passwd", "file").readsFromFile)
        assertTrue(parse("grep", "-nf", "/etc/passwd", "file").readsFromFile)
        assertTrue(parse("grep", "--file=/etc/passwd", "file").readsFromFile)
        assertTrue(parse("grep", "--fil", "/etc/passwd", "file").readsFromFile)
        assertTrue(parse("wc", "--files0-from=list").readsFromFile)
        assertTrue(parse("wc", "--files0=list").readsFromFile)
        assertTrue(parse("du", "--files0-from", "list").readsFromFile)
        assertFalse(parse("grep", "-n", "foo", "file").readsFromFile)
    }

    @Test
    fun `an option value is not mistaken for an option`() {
        val parsed = parse("grep", "-e", "-r", "file")
        assertFalse(parsed.isRecursive)
        assertEquals(listOf("-r"), parsed.optionValues)
        assertEquals(listOf("file"), parsed.operands)
    }

    @Test
    fun `double dash ends option parsing`() {
        val parsed = parse("grep", "--", "-r", "file")
        assertFalse(parsed.isRecursive)
        assertEquals(listOf("-r", "file"), parsed.operands)
        assertEquals(listOf("-f"), parse("cat", "--", "-f").operands)
    }

    @Test
    fun `a missing required value fails closed`() {
        assertTrue(parse("grep", "foo", "-f").hasUnrecognized)
        assertTrue(parse("head", "-n").hasUnrecognized)
    }

    @Test
    fun `head and tail numeric counts and tail follow`() {
        val head = parse("head", "-5", "notes.txt")
        assertFalse(head.hasUnrecognized)
        assertEquals(listOf("notes.txt"), head.operands)
        val lines = parse("head", "-n", "5", "notes.txt")
        assertEquals(listOf("5"), lines.optionValues)
        assertEquals(listOf("notes.txt"), lines.operands)
        val follow = parse("tail", "-f", "app.log")
        assertFalse(follow.readsFromFile)
        assertEquals(listOf("app.log"), follow.operands)
    }

    @Test
    fun `du always walks directories`() {
        assertTrue(parse("du", "-sh", "/tmp").isRecursive)
        assertFalse(parse("du", "-sh", "/tmp").hasUnrecognized)
    }

    @Test
    fun `date reads a file with -f and takes format operands`() {
        assertTrue(parse("date", "-f", "dates.txt").readsFromFile)
        val format = parse("date", "+%Y-%m-%d")
        assertFalse(format.hasUnrecognized)
        assertEquals(listOf("+%Y-%m-%d"), format.operands)
    }

    @Test
    fun `generic vetted programs accept flags without failing closed`() {
        val echo = parse("echo", "-n", "hi")
        assertFalse(echo.hasUnrecognized)
        assertFalse(echo.isRecursive)
        assertEquals(listOf("hi"), echo.operands)
        assertFalse(parse("readlink", "-f", "x").readsFromFile)
        assertFalse(parse("uname", "-a").isRecursive)
    }

    @Test
    fun `a glued value in a generic vetted program's short cluster is an option value, never flag letters`() {
        val glued = parse("stat", "-L/etc/passwd")
        assertFalse(glued.hasUnrecognized)
        assertEquals(listOf("/etc/passwd"), glued.optionValues, "the tail reaches the floor as a candidate")
        assertEquals(listOf("-L"), glued.options.map { it.name })
        assertEquals(listOf(","), parse("cut", "-d,", "x").optionValues)
        assertEquals(listOf("1"), parse("cut", "-f1", "x").optionValues)
        assertEquals(listOf("1-5"), parse("cut", "-c1-5", "x").optionValues)
        assertEquals(listOf("%z"), parse("stat", "-f%z", "x").optionValues)
        assertEquals(listOf("~"), parse("readlink", "-f~").optionValues)
        assertFalse(parse("seq", "-s,", "3").hasUnrecognized)
        assertFalse(parse("stat", "-Lx", "f").hasUnrecognized, "letters alone are still plain flags")
        assertTrue(parse("stat", "-Lx", "f").optionValues.isEmpty())
        assertTrue(
            parse("stat", "-/etc/passwd").hasUnrecognized,
            "a value with no option letter before it fails closed",
        )
    }

    @Test
    fun `program is matched by basename and unknown programs fail closed on any option`() {
        assertFalse(parse("/bin/ls", "-la").hasUnrecognized)
        val python = parse("python3", "x.py")
        assertFalse(python.hasUnrecognized)
        assertEquals(listOf("x.py"), python.operands)
        assertTrue(parse("python3", "-c", "print(1)").hasUnrecognized)
    }

    @Test
    fun `long options optional on any platform never swallow the next argument`() {
        assertTrue(parse("grep", "--context", "-R", "x", "/h").isRecursive)
        assertTrue(parse("grep", "--cont", "-R", "x", "/h").isRecursive)
        assertEquals(listOf("x", "/h"), parse("grep", "--context", "-R", "x", "/h").operands)
        assertEquals(listOf(ParsedOption("--context", "3")), parse("grep", "--context=3", "x").options)
        assertEquals(listOf(ParsedOption("-C", "3")), parse("grep", "-C", "3", "x").options)
        assertTrue(parse("grep", "--color", "-r", "x").isRecursive)
        assertTrue(parse("ls", "--color", "-R").isRecursive)
    }

    @Test
    fun `lone dash is an operand`() {
        assertEquals(listOf("-"), parse("cat", "-").operands)
    }

    @Test
    fun `without its table a program's every option is unrecognized and its other arguments are operands`() {
        val parsed = CommandOptions.parse(listOf("grep", "-e", "x", "--", "-r", "file"), withTable = false)

        assertTrue(parsed.hasUnrecognized)
        assertFalse(parsed.hasOptionTable)
        assertEquals(listOf("x", "-r", "file"), parsed.operands)
    }
}
