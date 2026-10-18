package org.foedusprogramme.alexandrite.tools.fs

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreeWalkTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a walk visits in name order, depth first, and stops after its most entries with a note`() {
        val fs = FsFixture(directory)
        listOf("b/2.txt", "b/1.txt", "a.txt", "c.txt", ".git/HEAD").forEach { fs.write("repo/$it", "") }
        val walk = TreeWalk(fs.floor, AgentId.MAIN, conversation = null, maxEntries = 4)
        val visited = mutableListOf<String>()

        walk.walk(fs.repository) {
            visited += it.relative
            true
        }

        assertEquals(listOf("a.txt", "b", "b/1.txt"), visited)
        assertEquals(listOf("The search stopped after 4 entries: give a narrower 'path'."), walk.notes)
    }

    @Test
    fun `a walk reads no more of a large directory than it may still visit`() {
        val fs = FsFixture(directory)
        fs.write("repo/a.txt", "")
        var read = 0
        val huge = object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
                override fun hasNext(): Boolean = read < 1_000

                override fun next(): Path = fs.repository.resolve("big-${read++}")

                override fun remove() = throw UnsupportedOperationException()
            }

            override fun close() {}
        }
        val walk = TreeWalk(fs.floor, AgentId.MAIN, conversation = null, maxEntries = 10) { directory ->
            if (directory == fs.repository) huge else Files.newDirectoryStream(directory)
        }

        walk.walk(fs.repository) { true }

        assertEquals(11, read)
        assertTrue(walk.exhausted)
    }
}
