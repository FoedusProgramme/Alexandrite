package org.unitedsoftware.alexandrite.internal.path

import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AtomicWritesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the file is replaced by a new one with what was written`() {
        val file = directory.resolve("notes.md").also { it.writeText("Old.") }
        val before = Files.readAttributes(file, "unix:ino")["ino"]

        writeAtomically(file) { it.write("New.".toByteArray()) }

        assertEquals("New.", file.readText())
        assertNotEquals(before, Files.readAttributes(file, "unix:ino")["ino"])
        assertEquals(listOf(file), directory.listDirectoryEntries())
    }

    @Test
    fun `a write that fails midway leaves the file as it was and no other file behind`() {
        val file = directory.resolve("notes.md").also { it.writeText("Old.") }

        val error = assertFailsWith<IOException> {
            writeAtomically(file) { output ->
                output.write("Half of it".toByteArray())
                output.close()
                throw IOException("The disk is full.")
            }
        }

        assertEquals("The disk is full." to "Old.", error.message to file.readText())
        assertEquals(listOf(file), directory.listDirectoryEntries())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the new file keeps the permissions of the one it replaces, and a new one is owner-only`() {
        val file = directory.resolve("script.sh").also { it.writeText("echo old") }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x---"))
        val created = directory.resolve("created.md")

        writeAtomically(file) { it.write("echo new".toByteArray()) }
        writeAtomically(created) { it.write("New.".toByteArray()) }

        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(created)))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the temporary file is never wider than the target while its content is written`() {
        val file = directory.resolve("secret.env").also { it.writeText("OLD=1") }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        lateinit var duringWrite: String

        writeAtomically(file) { output ->
            val temporary = directory.listDirectoryEntries().single { it != file }
            duringWrite = PosixFilePermissions.toString(Files.getPosixFilePermissions(temporary))
            output.write("NEW=2".toByteArray())
        }

        assertEquals("rw-------", duringWrite)
        assertEquals("NEW=2", file.readText())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a target name near the filesystem's name length limit can still be written`() {
        val file = directory.resolve("x".repeat(250) + ".txt")

        writeAtomically(file) { it.write("content".toByteArray()) }

        assertEquals("content", file.readText())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a symbolic link is replaced itself and its target is left alone`() {
        val target = directory.resolve("target.md").also { it.writeText("Target.") }
        val link = Files.createSymbolicLink(directory.resolve("link.md"), target)

        writeAtomically(link) { it.write("New.".toByteArray()) }

        assertEquals("Target." to "New.", target.readText() to link.readText())
        assertTrue(Files.isRegularFile(link, LinkOption.NOFOLLOW_LINKS))
    }
}
