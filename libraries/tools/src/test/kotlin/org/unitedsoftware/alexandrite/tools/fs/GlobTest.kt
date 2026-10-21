package org.unitedsoftware.alexandrite.tools.fs

import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GlobTest {
    private fun matching(pattern: String, vararg paths: String): List<String> =
        Glob.of(pattern, "pattern").let { glob -> paths.filter(glob::matches) }

    private val paths = arrayOf("A.kt", "src/A.kt", "src/main/B.kt", "src/main/b.KT", "src", "docs/a b.md", "x?y")

    @Test
    fun `a star stays within a segment and a double star spans whole segments, none too`() {
        assertEquals(listOf("A.kt"), matching("*.kt", *paths))
        assertEquals(listOf("A.kt", "src/A.kt", "src/main/B.kt"), matching("**/*.kt", *paths))
        assertEquals(listOf("src/A.kt", "src/main/B.kt"), matching("src/**/*.kt", *paths))
        assertEquals(listOf("src/A.kt", "src/main/B.kt", "src/main/b.KT"), matching("src/**", *paths))
        assertEquals(paths.toList(), matching("**", *paths))
        assertEquals(listOf("A.kt"), matching("./A.kt", *paths))
        assertEquals(listOf("A.kt"), matching("A**", *paths))
    }

    @Test
    fun `question marks, sets, alternatives and quoted characters match as in a shell`() {
        assertEquals(listOf("A.kt"), matching("?.kt", *paths))
        assertEquals(listOf("src/main/B.kt", "src/main/b.KT"), matching("src/main/[Bb].*", *paths))
        assertEquals(listOf("src/main/b.KT"), matching("src/main/[!B]*", *paths))
        assertEquals(listOf("src/main/b.KT"), matching("**/*.{KT,Kt}", *paths))
        assertEquals(listOf("A.kt", "src/A.kt", "src/main/B.kt", "src"), matching("{src,**/*.kt}", *paths))
        assertEquals(listOf("docs/a b.md"), matching("docs/a b.md", *paths))
        assertEquals(listOf("x?y"), matching("x\\?y", *paths))
        assertEquals(emptyList(), matching("src/[/]", "src//"))
        assertEquals(listOf("[x", "{y,z"), listOf("[x", "{y,z").filter { matching(it, it).isNotEmpty() })
    }

    @Test
    fun `a set matches no slash, negated or not`() {
        assertEquals(listOf("axc"), matching("a[!b]c", "a/c", "abc", "axc"))
        assertEquals(emptyList(), matching("src[!x]main/*", "src/main/A.kt"))
        assertEquals(emptyList(), matching("a[!]]c", "a/c"))
    }

    @Test
    fun `a blank or absolute pattern is refused`() {
        for (pattern in listOf(" ", "/src/*.kt", "~/notes")) {
            assertFailsWith<ToolRefusal>(pattern) { Glob.of(pattern, "pattern") }
        }
    }
}
