package org.unitedsoftware.alexandrite.tools.exec

import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ChainsTest {
    private fun steps(vararg argv: String): List<Pair<SegmentJoint?, List<String>>>? =
        shellChain(argv.toList(), "/home/someone")?.steps?.map { it.joint to it.words }

    @Test
    fun `a POSIX shell's -c script of words, quotes, chain operators and cd is read step by step`() {
        assertEquals(
            listOf(
                null to listOf("cd", "sub"),
                SegmentJoint.AND to listOf("git", "status"),
                SegmentJoint.AND to listOf("ls"),
            ),
            steps("sh", "-c", "cd sub && git status && ls"),
        )
        assertEquals(
            listOf(
                null to listOf("echo", "a b", "c\$d"),
                SegmentJoint.OR to listOf("echo", "x\"y"),
                SegmentJoint.THEN to listOf("ls", "/home/someone/notes"),
                SegmentJoint.THEN to listOf("pwd"),
                SegmentJoint.AND to listOf("true"),
            ),
            steps("bash", "-ec", "echo \"a b\" 'c\$d' || echo x\\\"y; ls ~/notes\npwd &&\n  true\n"),
        )
        assertEquals(listOf(null to listOf("ls")), steps("busybox", "sh", "-c", "ls"))
        assertEquals(listOf(null to listOf("ls")), steps("/bin/zsh", "--norc", "-e", "-c", "ls"))
        assertEquals(true, shellChain(listOf("sh", "-e", "-c", "ls"), null)?.errexit)
        assertEquals(false, shellChain(listOf("sh", "-c", "ls"), null)?.errexit)
    }

    @Test
    fun `any other shell syntax, shell or flag leaves the call as it is`() {
        val scripts = listOf(
            "echo \$HOME", "echo \"\$HOME\"", "echo `id`", "echo \$(id)", "ls | wc", "ls > out", "cat < in",
            "ls *.txt", "ls file?", "ls [ab]", "echo {a,b}", "(cd x && ls)", "ls &", "a=1 ls", "export A=1",
            "cd", "cd -", "cd -P x", "cd a b", "if true; then ls; fi", "ls ;; ls", "; ls", "ls &&", "ls || ",
            "ls # note", "ls ~other", "! ls", "echo 'open", "echo \"open", "eval ls", "source x", "exit 1",
        )
        for (script in scripts) assertNull(steps("sh", "-c", script), script)
        assertNull(steps("fish", "-c", "ls"))
        assertNull(steps("csh", "-c", "ls"))
        assertNull(steps("sh", "-l", "-c", "ls"))
        assertNull(steps("bash", "-i", "-c", "ls"))
        assertNull(steps("bash", "--login", "-c", "ls"))
        assertNull(steps("sh", "-c", "ls", "name"))
        assertNull(steps("sh", "script.sh"))
    }

    @Test
    fun `env, timeout and the other wrappers come off with their simple options`() {
        val unwrapped = unwrap(listOf("env", "A=1", "timeout", "5", "nice", "-n", "5", "nohup", "git", "status"))!!
        assertEquals(listOf("git", "status"), unwrapped.argv)
        assertEquals(mapOf("A" to "1"), unwrapped.environment)
        assertEquals(5.seconds, unwrapped.timeout)

        assertEquals(1.5.seconds * 60, unwrap(listOf("timeout", "-k", "1", "1.5m", "make"))?.timeout)
        assertNull(unwrap(listOf("timeout", "0", "make"))?.timeout)
        assertEquals(listOf("make"), unwrap(listOf("time", "-p", "stdbuf", "-oL", "caffeinate", "-i", "make"))?.argv)
        assertEquals(listOf("ls"), unwrap(listOf("ls"))?.argv)
    }

    @Test
    fun `a wrapper with an option it may not take, or with nothing to wrap, stays whole`() {
        val calls = listOf(
            listOf("env", "-i", "ls"),
            listOf("env"),
            listOf("env", "A=1"),
            listOf("timeout", "soon", "ls"),
            listOf("timeout", "5"),
            listOf("time", "-o", "out", "ls"),
            listOf("caffeinate", "-w", "1", "ls"),
            listOf("ionice", "-p", "1"),
        )
        for (call in calls) assertNull(unwrap(call), "$call")
        for (call in listOf("sudo ls", "doas ls", "su -c ls", "xargs ls", "ssh host ls")) {
            assertEquals(call.split(' '), unwrap(call.split(' '))?.argv, call)
        }
    }
}
