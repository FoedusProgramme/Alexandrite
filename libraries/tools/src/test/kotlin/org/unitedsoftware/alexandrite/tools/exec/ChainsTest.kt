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
    fun `a pipe joins programs as steps of their own`() {
        assertEquals(
            listOf(
                null to listOf("ls"),
                SegmentJoint.PIPE to listOf("grep", "x"),
                SegmentJoint.PIPE to listOf("wc", "-l"),
                SegmentJoint.OR to listOf("echo", "none"),
            ),
            steps("sh", "-c", "ls|grep x |\n  wc -l || echo none"),
        )
    }

    @Test
    fun `redirects that only join output or discard it are kept with their step, with or without a space`() {
        val redirects = mapOf(
            "2>&1" to listOf(Redirect.ERRORS_TO_OUTPUT),
            "1>&2" to listOf(Redirect.OUTPUT_TO_ERRORS),
            ">&2" to listOf(Redirect.OUTPUT_TO_ERRORS),
            ">/dev/null" to listOf(Redirect.OUTPUT_DISCARDED),
            "1> /dev/null" to listOf(Redirect.OUTPUT_DISCARDED),
            "2>/dev/null" to listOf(Redirect.ERRORS_DISCARDED),
            "&> /dev/null" to listOf(Redirect.BOTH_DISCARDED),
            ">/dev/null 2>&1" to listOf(Redirect.OUTPUT_DISCARDED, Redirect.ERRORS_TO_OUTPUT),
            "< /dev/null" to listOf(Redirect.NO_INPUT),
            "0</dev/null" to listOf(Redirect.NO_INPUT),
        )
        for ((redirect, expected) in redirects) {
            val chain = shellChain(listOf("bash", "-c", "make $redirect && ls"), null)
            assertEquals(listOf("make"), chain?.steps?.first()?.words, redirect)
            assertEquals(expected, chain?.steps?.first()?.redirects, redirect)
        }
        val glued = shellChain(listOf("sh", "-c", "git clone x 2>&1|tail -3"), null)!!.steps
        assertEquals(listOf(listOf("git", "clone", "x"), listOf("tail", "-3")), glued.map { it.words })
        assertEquals(listOf(listOf(Redirect.ERRORS_TO_OUTPUT), emptyList()), glued.map { it.redirects })
        assertEquals(listOf("echo", "a2"), steps("sh", "-c", "echo a2>/dev/null")?.single()?.second)
        assertEquals(listOf("echo", "2"), steps("sh", "-c", "echo '2'>/dev/null")?.single()?.second)
        assertEquals(2, steps("zsh", "-c", "make 2>&1 | tail -3")?.size)
    }

    @Test
    fun `the words with unquoted wildcards are kept as patterns, which zsh never passes as they are`() {
        val step = shellChain(listOf("sh", "-c", "grep -r x --include=*.kt 'a*' file?"), null)!!.steps.single()

        assertEquals(listOf("grep", "-r", "x", "--include=*.kt", "a*", "file?"), step.words)
        assertEquals(listOf("--include=*.kt", "file?"), step.patterns)
        assertNull(steps("zsh", "-c", "ls *.txt"))
    }

    @Test
    fun `a script that a person runs to look into a repository is read as its nine programs`() {
        val script = "rm -rf /tmp/alx2; git clone --depth 1 --quiet https://github.com/x/y /tmp/alx2 2>&1 | " +
            "tail -3; cd /tmp/alx2 && echo '--- files:'; grep -rli 'taint' --include=*.kt . | head -30; " +
            "grep -rn -i 'taint' --include=*.kt libraries | wc -l"

        assertEquals(
            listOf(
                null to listOf("rm", "-rf", "/tmp/alx2"),
                SegmentJoint.THEN to
                    listOf("git", "clone", "--depth", "1", "--quiet", "https://github.com/x/y", "/tmp/alx2"),
                SegmentJoint.PIPE to listOf("tail", "-3"),
                SegmentJoint.THEN to listOf("cd", "/tmp/alx2"),
                SegmentJoint.AND to listOf("echo", "--- files:"),
                SegmentJoint.THEN to listOf("grep", "-rli", "taint", "--include=*.kt", "."),
                SegmentJoint.PIPE to listOf("head", "-30"),
                SegmentJoint.THEN to listOf("grep", "-rn", "-i", "taint", "--include=*.kt", "libraries"),
                SegmentJoint.PIPE to listOf("wc", "-l"),
            ),
            steps("sh", "-c", script),
        )
    }

    @Test
    fun `any other shell syntax, shell or flag leaves the call as it is`() {
        val scripts = listOf(
            "echo \$HOME", "echo \"\$HOME\"", "echo `id`", "echo \$(id)", "ls > out", "cat < in", "ls >> log",
            "ls >/dev/null2", "ls >'/dev/null'", "ls 2>&3", "ls >&-", "ls 3>/dev/null", "ls 10>/dev/null",
            "ls 2</dev/null",
            "cat <<EOF", "cat <<< x", "cat <(ls)", "ls >(cat)", "ls <> f", "ls >| f", "ls |& wc", "ls &>/dev/null",
            "ls &>> /dev/null", ">/dev/null", "ls | ", "| wc", "ls | | wc", "cd x | ls", "ls | cd x",
            "cd x 2>/dev/null", "cd *", "ls [ab]", "echo {a,b}", "(cd x && ls)", "ls &", "a=1 ls", "export A=1",
            "cd", "cd -", "cd -P x", "cd a b", "if true; then ls; fi", "ls ;; ls", "; ls", "ls &&", "ls || ",
            "ls # note", "ls ~other", "! ls", "echo 'open", "echo \"open", "eval ls", "source x", "exit 1",
        )
        for (script in scripts) assertNull(steps("sh", "-c", script), script)
        assertNull(steps("dash", "-c", "ls &>/dev/null"))
        assertNull(steps("zsh", "-c", "ls >/dev/null | wc"))
        assertNull(steps("zsh", "-c", "ls 2>&1 2>/dev/null"))
        assertNull(steps("zsh", "-c", "ls | wc </dev/null"))
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
