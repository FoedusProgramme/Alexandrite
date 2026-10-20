package org.unitedsoftware.alexandrite.sandbox.bubblewrap

import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StageScriptTest {
    private val filter = checkNotNull(seccompFilter("aarch64"))

    @Test
    fun `the script starts the relay's inner end on the proxy's port and waits for it to listen`() {
        val script = stageScript("/usr/bin/bwrap", "/usr/bin/socat", Path.of("/run/user/1/a/1.sock") to 4321, filter)

        assertContains(
            script,
            "'/usr/bin/socat' TCP4-LISTEN:4321,bind=127.0.0.1,reuseaddr,fork,backlog=1024 'UNIX-CONNECT:/run/user/1/a/1.sock'",
        )
        assertContains(script, "\"\$local\" = 0100007F:10E1")
        assertContains(script, "'/usr/bin/bwrap' --unshare-user --unshare-pid --dev-bind / / --proc /proc")
        assertContains(script, "--seccomp 3 -- \"\$@\" 3<&0 0<&4 4<&-")
    }

    @Test
    fun `without a relay no socat starts, and an unusable socket path is refused`() {
        assertFalse("socat" in stageScript("/usr/bin/bwrap", "/usr/bin/socat", null, filter))
        assertFailsWith<IllegalArgumentException> {
            stageScript("/usr/bin/bwrap", "/usr/bin/socat", Path.of("/tmp/a,b.sock") to 1, filter)
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the shell's printf writes the filter's bytes exactly`() {
        val line = stageScript("/usr/bin/bwrap", "/usr/bin/socat", null, filter).lines().first {
            it.startsWith("printf")
        }
        val printf = line.substringBefore(" | ")
        val process = ProcessBuilder("/bin/sh", "-c", printf).start()

        assertContentEquals(filter, process.inputStream.readAllBytes())
    }
}
