package org.foedusprogramme.alexandrite.tools.fs

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TreeWalkTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a walk visits in name order, depth first, and stops after its most entries with a note`() {
        val fs = FsFixture(directory)
        listOf("b/2.txt", "b/1.txt", "a.txt", "c.txt", ".git/HEAD").forEach { fs.write("repo/$it", "") }
        val walk = TreeWalk(fs.floor, AgentId.MAIN, maxEntries = 4)
        val visited = mutableListOf<String>()

        walk.walk(fs.repository) {
            visited += it.relative
            true
        }

        assertEquals(listOf("a.txt", "b", "b/1.txt"), visited)
        assertEquals(listOf("The search stopped after 4 entries: give a narrower 'path'."), walk.notes)
    }
}
