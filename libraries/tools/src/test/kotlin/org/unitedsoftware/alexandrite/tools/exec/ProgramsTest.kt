package org.unitedsoftware.alexandrite.tools.exec

import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.unitedsoftware.alexandrite.internal.path.Platform
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramsTest {
    private fun opaque(vararg argv: String): Boolean = isOpaque(argv.first(), argv.drop(1))

    @Test
    fun `launchers and shells are opaque whatever their arguments`() {
        val launchers = listOf("env", "sh", "bash", "zsh", "dash", "xargs", "sudo", "nohup", "timeout", "time", "ssh")

        for (launcher in launchers) assertTrue(opaque(launcher), launcher)
        assertTrue(opaque("sh", "build.sh"))
        assertTrue(opaque("osascript", "-e", "beep"))
    }

    @Test
    fun `awk programs and find with an exec action are opaque`() {
        for (awk in listOf("awk", "gawk", "mawk", "nawk")) assertTrue(opaque(awk, "{print}", "file"), awk)
        for (action in listOf("-exec", "-execdir", "-ok", "-okdir")) assertTrue(opaque("find", ".", action, "id", ";"))
        assertFalse(opaque("find", ".", "-name", "*.kt"))
    }

    @Test
    fun `python is opaque when given code with -c`() {
        assertTrue(opaque("python3", "-c", "print(1)"))
        assertTrue(opaque("python", "-Ic", "print(1)"))
        assertTrue(opaque("python3.12", "-c", "print(1)"))
        assertTrue(opaque("pypy3", "-cprint(1)"))
        assertTrue(opaque("python3", "-W", "ignore", "-c", "print(1)"))
        assertFalse(opaque("python3", "script.py", "-c", "config.yml"))
        assertFalse(opaque("python3", "-m", "http.server", "-c"))
        assertFalse(opaque("python3", "-Wdefault::DeprecationWarning", "script.py"))
        assertFalse(opaque("python3", "-X", "c", "script.py"))
    }

    @Test
    fun `node and bun are opaque when given code to evaluate or print`() {
        for (code in listOf(listOf("-e", "1"), listOf("-p", "1"), listOf("--eval", "1"), listOf("--print=1"))) {
            assertTrue(opaque("node", *code.toTypedArray()), "$code")
            assertTrue(opaque("bun", *code.toTypedArray()), "$code")
        }
        assertTrue(opaque("nodejs", "-pe", "1"))
        assertTrue(opaque("node", "-r", "esm", "-e", "1"))
        assertTrue(opaque("node", "--title", "x", "-e", "1"))
        assertFalse(opaque("node", "-r", "esm", "app.js", "-e"))
        assertFalse(opaque("node", "--check", "app.js"))
    }

    @Test
    fun `deno is opaque for eval`() {
        assertTrue(opaque("deno", "eval", "console.log(1)"))
        assertTrue(opaque("deno", "repl", "--eval", "1"))
        assertFalse(opaque("deno", "run", "main.ts"))
    }

    @Test
    fun `ruby, perl, php, lua and R are opaque when given code`() {
        val coded = listOf(
            listOf("ruby", "-e", "puts 1"),
            listOf("ruby", "-Ilib", "-rjson", "-e", "puts 1"),
            listOf("perl", "-e", "print 1"),
            listOf("perl", "-E", "say 1"),
            listOf("perl", "-le", "print 1"),
            listOf("perl", "-0777", "-ne", "print"),
            listOf("perl", "-CSDe", "print 1"),
            listOf("perl", "-pi", "-e", "s/a/b/", "file"),
            listOf("php", "-r", "echo 1;"),
            listOf("php", "-R", "echo \$argn;"),
            listOf("lua", "-e", "print(1)"),
            listOf("luajit", "-l", "socket", "-e", "print(1)"),
            listOf("Rscript", "-e", "1"),
            listOf("R", "--no-echo", "-e", "1"),
        )
        val scripts = listOf(
            listOf("ruby", "-W:no-deprecated", "app.rb"),
            listOf("ruby", "-ie", "app.rb"),
            listOf("perl", "-MData::Dumper", "script.pl"),
            listOf("perl", "-ie", "script.pl"),
            listOf("php", "-d", "memory_limit=1G", "app.php"),
            listOf("lua", "script.lua", "-e"),
            listOf("Rscript", "--vanilla", "script.R"),
        )

        for (argv in coded) assertTrue(opaque(*argv.toTypedArray()), "$argv")
        for (argv in scripts) assertFalse(opaque(*argv.toTypedArray()), "$argv")
    }

    @Test
    fun `Windows shells, script hosts and code runners are opaque however Windows spells their names`() {
        val coded = listOf(
            listOf("CMD.EXE", "/c", "dir"),
            listOf("C:\\Windows\\System32\\cmd.exe", "/c", "dir"),
            listOf("PowerShell", "-NoProfile", "-Command", "Get-Item x"),
            listOf("pwsh.exe", "-c", "x"),
            listOf("wscript", "a.vbs"),
            listOf("CScript.exe", "a.js"),
            listOf("mshta", "a.hta"),
            listOf("rundll32.exe", "a.dll,Run"),
            listOf("wsl", "ls"),
            listOf("bash.exe", "-c", "ls"),
            listOf("py", "-3", "-c", "print(1)"),
            listOf("Python.exe", "-c", "print(1)"),
            listOf("R.exe", "-e", "1"),
        )

        for (argv in coded) assertTrue(isOpaque(Platform.WINDOWS.programName(argv.first()), argv.drop(1)), "$argv")
        assertFalse(isOpaque(Platform.WINDOWS.programName("Git.exe"), listOf("status")))
        assertFalse(isOpaque(Platform.POSIX.programName("CMD.EXE"), listOf("/c", "dir")))
    }

    @Test
    fun `a Windows shell's command line is no POSIX script that is run step by step`() {
        assertNull(shellChain(listOf("cmd", "/c", "echo a && echo b"), null))
        assertNull(shellChain(listOf("powershell", "-c", "echo a; echo b"), null))
    }

    @Test
    fun `other programs are not opaque`() {
        for (argv in listOf(listOf("ls", "-la"), listOf("git", "status"), listOf("./gradlew", "test"))) {
            assertFalse(opaque(argv.first().substringAfterLast('/'), *argv.drop(1).toTypedArray()), "$argv")
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a program is searched in the system directories first, then the package managers', then PATH`() {
        val search = searchPath(mapOf("PATH" to "/opt/tools/bin:relative/bin:/usr/bin"))

        assertEquals(
            listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin", "/opt/homebrew/bin", "/usr/local/bin", "/opt/tools/bin"),
            search.map(Path::toString),
        )
    }
}
