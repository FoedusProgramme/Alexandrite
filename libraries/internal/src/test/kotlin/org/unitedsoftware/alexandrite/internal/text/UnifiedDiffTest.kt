package org.unitedsoftware.alexandrite.internal.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedDiffTest {
    private fun numbers(count: Int, vararg changed: Pair<Int, String>): String {
        val replaced = changed.toMap()
        return (1..count).joinToString("") { "${replaced[it] ?: it}\n" }
    }

    private fun diff(old: String, new: String, context: Int = 3): String =
        UnifiedDiff.of(old, new, "a.txt", "b.txt", context).text

    @Test
    fun `a changed line shows with three lines of context on each side`() {
        val diff = UnifiedDiff.of(numbers(10), numbers(10, 5 to "five"), "a.txt", "b.txt")

        assertEquals(
            """
            --- a.txt
            +++ b.txt
            @@ -2,7 +2,7 @@
             2
             3
             4
            -5
            +five
             6
             7
             8

            """.trimIndent(),
            diff.text,
        )
        assertEquals(1 to 1, diff.added to diff.removed)
    }

    @Test
    fun `changes at most six unchanged lines apart share a hunk and farther ones get their own`() {
        val apart = diff(numbers(20), numbers(20, 3 to "three", 11 to "eleven"))
        val close = diff(numbers(20), numbers(20, 3 to "three", 10 to "ten"))

        assertEquals(
            """
            --- a.txt
            +++ b.txt
            @@ -1,6 +1,6 @@
             1
             2
            -3
            +three
             4
             5
             6
            @@ -8,7 +8,7 @@
             8
             9
             10
            -11
            +eleven
             12
             13
             14

            """.trimIndent(),
            apart,
        )
        assertEquals(
            listOf("@@ -1,13 +1,13 @@", " 1", " 2", "-3", "+three", " 4", " 5", " 6", " 7", " 8", " 9", "-10", "+ten"),
            close.lines().drop(2).take(13),
        )
    }

    @Test
    fun `within a change the removed lines come before the added ones`() {
        assertEquals(
            "--- a.txt\n+++ b.txt\n@@ -1,4 +1,4 @@\n a\n-b\n-c\n+B\n+C\n d\n",
            diff("a\nb\nc\nd\n", "a\nB\nC\nd\n"),
        )
    }

    @Test
    fun `a last line without a line break is marked, and a line break added to it is a change`() {
        assertEquals(
            "--- a.txt\n+++ b.txt\n@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+b\n",
            diff("a\nb", "a\nb\n"),
        )
        assertEquals(
            "--- a.txt\n+++ b.txt\n@@ -1,2 +1,2 @@\n-a\n+x\n b\n\\ No newline at end of file\n",
            diff("a\nb", "x\nb"),
        )
    }

    @Test
    fun `an empty side starts at the line before it and a side of one line shows no count`() {
        assertEquals("--- a.txt\n+++ b.txt\n@@ -0,0 +1,2 @@\n+a\n+b\n", diff("", "a\nb\n"))
        assertEquals("--- a.txt\n+++ b.txt\n@@ -1 +0,0 @@\n-a\n", diff("a\n", ""))
        assertEquals("--- a.txt\n+++ b.txt\n@@ -0,0 +1 @@\n+x\n", diff("a\nb\n", "x\na\nb\n", context = 0))
        assertEquals("--- a.txt\n+++ b.txt\n@@ -2,0 +3 @@\n+c\n", diff("a\nb\n", "a\nb\nc\n", context = 0))
    }

    @Test
    fun `equal texts make no diff`() {
        val diff = UnifiedDiff.of("a\nb\n", "a\nb\n", "a.txt", "b.txt")

        assertEquals(Triple("", 0, 0), Triple(diff.text, diff.added, diff.removed))
    }

    @Test
    fun `random texts get the shortest diffs, which patch one into the other`() {
        val random = Random(7)
        repeat(300) { round ->
            fun text() = List(random.nextInt(0, 25)) { "abcd"[random.nextInt(4)] }.joinToString("") { "$it\n" } +
                if (random.nextBoolean()) "" else "${"abcd"[random.nextInt(4)]}"
            val old = text()
            val new = text()

            val diff = UnifiedDiff.of(old, new, "a.txt", "b.txt", context = random.nextInt(0, 4))

            val common = longestCommon(lines(old), lines(new))
            assertEquals(
                (lines(new).size - common) to (lines(old).size - common),
                diff.added to diff.removed,
                "round $round",
            )
            assertEquals(new, patched(old, diff.text), "round $round")
        }
    }

    @Test
    fun `texts with nothing in common are replaced whole, quickly`() {
        val old = (1..20_000).joinToString("") { "old $it\n" }
        val new = (1..20_000).joinToString("") { "new $it\n" }

        val diff = UnifiedDiff.of(old, new, "a.txt", "b.txt")

        assertEquals(20_000 to 20_000, diff.added to diff.removed)
        assertEquals("@@ -1,20000 +1,20000 @@", diff.text.lines()[2])
    }
}

/** [text]'s lines, each with its line break. */
private fun lines(text: String): List<String> = Regex("[^\n]*\n|[^\n]+").findAll(text).map { it.value }.toList()

private fun longestCommon(a: List<String>, b: List<String>): Int {
    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            lengths[i][j] = if (a[i] == b[j]) 1 + lengths[i + 1][j + 1] else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
    }
    return lengths[0][0]
}

/** [old] with the hunks of [diff] applied, as `patch` would. */
private fun patched(old: String, diff: String): String {
    if (diff.isEmpty()) return old
    val source = lines(old)
    val result = StringBuilder()
    var next = 0
    val rows = diff.removeSuffix("\n").split("\n").drop(2)
    var index = 0
    while (index < rows.size) {
        val header = Regex("""@@ -(\d+)(?:,(\d+))? \+\d+(?:,\d+)? @@""").matchEntire(rows[index++])!!
        val start = header.groupValues[1].toInt()
        val count = header.groupValues[2].ifEmpty { "1" }.toInt()
        val first = if (count == 0) start else start - 1
        while (next < first) result.append(source[next++])
        while (index < rows.size && !rows[index].startsWith("@@")) {
            val row = rows[index++]
            val ended = index >= rows.size || rows[index] != "\\ No newline at end of file"
            if (!ended) index++
            val line = row.substring(1) + if (ended) "\n" else ""
            when (row[0]) {
                ' ' -> result.append(source[next++].also { assertEquals(it, line) })
                '-' -> assertEquals(source[next++], line)
                '+' -> result.append(line)
            }
        }
    }
    while (next < source.size) result.append(source[next++])
    return result.toString()
}
