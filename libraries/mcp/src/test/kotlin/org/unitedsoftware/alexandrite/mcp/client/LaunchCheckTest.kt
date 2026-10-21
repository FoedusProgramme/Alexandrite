package org.unitedsoftware.alexandrite.mcp.client

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LaunchCheckTest {
    private fun warned(command: String, vararg args: String): String =
        assertNotNull(LaunchCheck.warning(command, args.toList()), "$command ${args.joinToString(" ")} is warned of")

    private fun quiet(command: String, vararg args: String) =
        assertNull(LaunchCheck.warning(command, args.toList()), "$command ${args.joinToString(" ")} is not warned of")

    @Test
    fun `ordinary servers start`() {
        quiet("npx", "-y", "@modelcontextprotocol/server-filesystem", "/srv/docs")
        quiet("uvx", "mcp-server-git")
        quiet("/usr/local/bin/my-server", "--port", "0")
        quiet("node", "server.js", "-e", "production")
        quiet("node", "--require", "dotenv/config", "server.js")
        quiet("python3", "-u", "server.py", "-c", "config.yaml")
        quiet("python", "-m", "mcp_server", "-c", "x")
        quiet("python3", "-W", "ignore::DeprecationWarning", "server.py")
        quiet("bash", "server.sh")
        quiet("bash", "-o", "pipefail", "server.sh")
        quiet("deno", "run", "--allow-net", "server.ts")
        quiet("docker", "run", "-i", "--rm", "-v", "/srv/docs:/docs:ro", "mcp/filesystem")
        quiet("docker", "run", "--privileged=false", "image")
        quiet("C:\\Tools\\server.exe")
    }

    @Test
    fun `a command that is no name or absolute path is warned of`() {
        assertContains(warned("./server"), "relative path")
        assertContains(warned("bin/server"), "relative path")
        assertContains(warned("npx -y server"), "spaces")
    }

    @Test
    fun `a shell running code is warned of`() {
        for ((command, args) in listOf(
            "sh" to listOf("-c", "npx server"),
            "bash" to listOf("-lc", "npx server"),
            "/bin/zsh" to listOf("-e", "-c", "x"),
            "bash" to listOf("-o", "pipefail", "-c", "x"),
            "fish" to listOf("--command=x"),
            "dash" to listOf("-c", "x"),
            "cmd" to listOf("/c", "npx server"),
            "cmd.exe" to listOf("/C", "npx server"),
            "powershell" to listOf("-Command", "x"),
            "pwsh" to listOf("-c", "x"),
            "pwsh.exe" to listOf("-EncodedCommand", "eAA="),
        )) {
            assertContains(warned(command, *args.toTypedArray()), "runs the c")
        }
    }

    @Test
    fun `inline code of an interpreter is warned of`() {
        for ((command, args) in listOf(
            "node" to listOf("-e", "x"),
            "node" to listOf("--eval=x"),
            "node" to listOf("-p", "x"),
            "nodejs" to listOf("--print", "x"),
            "python" to listOf("-c", "x"),
            "python3" to listOf("-uc", "x"),
            "/usr/bin/python3.12" to listOf("-c", "x"),
            "deno" to listOf("eval", "x"),
            "bun" to listOf("-e", "x"),
            "perl" to listOf("-e", "x"),
            "perl" to listOf("-ne", "x"),
            "ruby" to listOf("-e", "x"),
            "php" to listOf("-r", "x"),
        )) {
            assertContains(warned(command, *args.toTypedArray()), "runs the code")
        }
    }

    @Test
    fun `a wrapping env is looked through`() {
        assertContains(warned("/usr/bin/env", "FOO=1", "bash", "-c", "x"), "bash runs the code")
        assertContains(warned("env", "-S", "bash -c x"), "env -S")
        quiet("env", "FOO=1", "npx", "server")
    }

    @Test
    fun `privileged containers are warned of`() {
        val cases = mapOf(
            listOf("run", "--privileged", "image") to "every privilege",
            listOf("run", "--pid=host", "image") to "pid namespace",
            listOf("run", "--net=host", "image") to "net namespace",
            listOf("run", "--network", "host", "image") to "network namespace",
            listOf("run", "-v", "/:/host", "image") to "host's root",
            listOf("run", "--volume=/:/host:ro", "image") to "host's root",
            listOf("run", "--mount", "type=bind,source=/,target=/host", "image") to "host's root",
            listOf("run", "--cap-add=ALL", "image") to "every capability",
            listOf("--context", "x", "run", "--cap-add", "all", "image") to "every capability",
        )
        for ((args, why) in cases) {
            assertContains(warned("docker", *args.toTypedArray()), why)
        }
        assertEquals(
            "podman run with --privileged is risky: it gives the container every privilege of the host.",
            warned("podman", "run", "--privileged", "image"),
        )
    }
}
