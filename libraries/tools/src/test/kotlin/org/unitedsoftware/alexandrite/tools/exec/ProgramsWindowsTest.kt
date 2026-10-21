package org.unitedsoftware.alexandrite.tools.exec

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
class ProgramsWindowsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a program named without an extension is found by the extension that runs it`() {
        val bin = directory.resolve("bin").createDirectories()
        val script = bin.resolve("hello.cmd").also { it.writeText("@echo off\r\n") }

        assertEquals(script, resolveProgram("hello", directory, listOf(bin)))
    }

    @Test
    fun `a program that a call names with a Windows separator is resolved as given`() {
        val script = directory.resolve("tools").createDirectories().resolve("hello.cmd")
        script.writeText("@echo off\r\n")

        assertEquals(script, resolveProgram("tools\\hello.cmd", directory, emptyList()))
        assertTrue(namesDirectory("tools\\hello.cmd"))
        assertTrue(namesDirectory("C:\\Windows\\System32\\cmd.exe"))
    }

    @Test
    fun `a file that Windows does not run as a program is no program`() {
        val bin = directory.resolve("bin").createDirectories()
        bin.resolve("notes.txt").writeText("hi")

        assertNull(resolveProgram("notes", directory, listOf(bin)))
    }

    @Test
    fun `PATH is read however Windows spells the variable, and its quoted and empty entries are read`() {
        val first = directory.resolve("first").createDirectories()
        val second = directory.resolve("second").createDirectories()

        val search = searchPath(mapOf("Path" to "\"$first\";;$second"))

        assertTrue(first.normalize() in search)
        assertTrue(second.normalize() in search)
        assertTrue(search.indexOf(first.normalize()) > 0, "$search")
        assertTrue(search.first().fileName.toString().equals("System32", ignoreCase = true), "$search")
    }

    @Test
    fun `the system directories of the host come from where it says Windows is`() {
        val directories = systemDirectories(mapOf("SystemRoot" to "C:\\Windows"))

        assertTrue(Path.of("C:\\Windows\\System32") in directories)
        assertTrue(Path.of("C:\\Windows") in directories)
        assertEquals(Path.of("C:\\Windows\\System32"), systemDirectories(emptyMap()).first())
    }

    @Test
    fun `a child gets the Windows variables that name its system, its files and its temporary ones`() {
        val parent = mapOf(
            "Path" to "C:\\bin",
            "SystemRoot" to "C:\\Windows",
            "TEMP" to "C:\\temp",
            "SEKRIT" to "hunter2",
        )

        val child = childEnvironment(parent, emptyList())

        assertEquals("C:\\bin", child.variable("PATH"))
        assertEquals("C:\\Windows", child["SystemRoot"])
        assertEquals("C:\\temp", child["TEMP"])
        assertNull(child["SEKRIT"])
    }

    @Test
    fun `a child without a PATH is given the system directories of the host`() {
        val child = childEnvironment(mapOf("SystemRoot" to "C:\\Windows"), emptyList())

        assertEquals(SYSTEM_DIRECTORIES.joinToString(File.pathSeparator), child.variable("PATH"))
    }

    @Test
    fun `a variable named by the configuration is passed however Windows spells it`() {
        val child = childEnvironment(mapOf("Search_Key" to "fixture-key"), listOf("SEARCH_KEY"))

        assertEquals("fixture-key", child["Search_Key"])
    }
}
