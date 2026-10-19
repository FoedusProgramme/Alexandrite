package org.unitedsoftware.alexandrite.internal.path

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RegularFilesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a regular file opens and a FIFO is refused without waiting for a writer`() {
        val file = Files.writeString(directory.resolve("notes.md"), "Notes.")
        val fifo = directory.resolve("pipe")
        val made = try {
            ProcessBuilder("mkfifo", "$fifo").start().waitFor() == 0
        } catch (e: IOException) {
            false
        }
        assumeTrue(made, "no mkfifo")

        assertEquals("Notes.", openRegularFile(file).use { String(it.readAllBytes()) })
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertFailsWith<NotRegularFileException> { openRegularFile(fifo) }
        }
    }
}
