package org.foedusprogramme.alexandrite.agent.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolGlobTest {
    @Test
    fun `a glob is dotted lowercase words with stars`() {
        val valid = listOf("*", "fs.*", "fs.read", "*.read", "fs*", "notes.find_all", "a.*.b", "**")
        val invalid = listOf("", "Fs.read", "fs..read", "fs.read.", ".fs", "fs-read", "fs.1read", "fs read", "fs/*")

        assertEquals(valid, valid.filter(ToolGlob::isValid))
        assertEquals(emptyList(), invalid.filter(ToolGlob::isValid))
        assertFailsWith<IllegalArgumentException> { ToolGlob.of("fs..read") }
    }

    @Test
    fun `a star stands for any characters, dots included`() {
        val names = listOf("fs.read", "fs.read.lines", "fsx.read", "notes.read", "fs")

        fun matched(glob: String) = names.filter(ToolGlob.of(glob)::matches)

        assertEquals(names, matched("*"))
        assertEquals(listOf("fs.read", "fs.read.lines"), matched("fs.*"))
        assertEquals(listOf("fs.read", "fs.read.lines", "fsx.read", "fs"), matched("fs*"))
        assertEquals(listOf("fs.read", "fsx.read", "notes.read"), matched("*.read"))
        assertEquals(listOf("fs.read"), matched("fs.read"))
        assertEquals("fs.*", ToolGlob.of("fs.*").toString())
    }
}
