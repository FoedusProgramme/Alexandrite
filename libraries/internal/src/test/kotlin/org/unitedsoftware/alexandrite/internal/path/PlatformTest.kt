package org.unitedsoftware.alexandrite.internal.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun `a Windows program name ignores case, the directory part and the extension that runs it`() {
        val names = mapOf(
            "C:\\Windows\\System32\\CMD.EXE" to "cmd",
            "Curl.exe" to "curl",
            "npm.cmd" to "npm",
            "build.BAT" to "build",
            "PowerShell" to "powershell",
            "tools/run.com" to "run",
            "notes.txt" to "notes.txt",
            "python3.12" to "python3.12",
        )

        for ((argv0, name) in names) assertEquals(name, Platform.WINDOWS.programName(argv0), argv0)
    }

    @Test
    fun `a POSIX program name is the last element as it is spelled`() {
        assertEquals("bash", Platform.POSIX.programName("/bin/bash"))
        assertEquals("CMD.EXE", Platform.POSIX.programName("CMD.EXE"))
        assertEquals("curl.exe", Platform.POSIX.programName("/opt/curl.exe"))
        assertEquals("a\\b", Platform.POSIX.programName("a\\b"))
    }

    @Test
    fun `a backslash separates paths on Windows only`() {
        assertTrue(Platform.WINDOWS.hasSeparator("tools\\run.cmd"))
        assertFalse(Platform.POSIX.hasSeparator("tools\\run.cmd"))
        assertTrue(Platform.POSIX.hasSeparator("tools/run"))
        assertFalse(Platform.WINDOWS.hasSeparator("run"))
        assertEquals("run.cmd", Platform.WINDOWS.fileName("C:\\tools/bin\\run.cmd"))
        assertEquals("b\\run.cmd", Platform.POSIX.fileName("/a/b\\run.cmd"))
    }

    @Test
    fun `a Windows path's keys compare its separators as slashes`() {
        assertEquals("C:/Users/user/Secret", nameKey("C:\\Users\\user\\Secret", false, Platform.WINDOWS))
        assertEquals("c:/users/user/secret", nameKey("C:\\Users\\user\\Secret", true, Platform.WINDOWS))
        assertEquals("/srv/a\\b", nameKey("/srv/a\\b", false, Platform.POSIX))
    }
}
