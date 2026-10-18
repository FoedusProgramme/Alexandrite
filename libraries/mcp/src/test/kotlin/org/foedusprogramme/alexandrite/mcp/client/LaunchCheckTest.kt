package org.foedusprogramme.alexandrite.mcp.client

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LaunchCheckTest {
    private fun refused(command: String, vararg args: String): String =
        assertNotNull(LaunchCheck.problem(command, args.toList()), "$command ${args.joinToString(" ")} is refused")

    private fun allowed(command: String, vararg args: String) =
        assertNull(LaunchCheck.problem(command, args.toList()), "$command ${args.joinToString(" ")} is allowed")

    @Test
    fun `ordinary servers start`() {
        allowed("npx", "-y", "@modelcontextprotocol/server-filesystem", "/srv/docs")
        allowed("uvx", "mcp-server-git")
        allowed("/usr/local/bin/my-server", "--port", "0")
        allowed("node", "server.js", "-e", "production")
        allowed("node", "--require", "dotenv/config", "server.js")
        allowed("python3", "-u", "server.py", "-c", "config.yaml")
        allowed("python", "-m", "mcp_server", "-c", "x")
        allowed("python3", "-W", "ignore::DeprecationWarning", "server.py")
        allowed("bash", "server.sh")
        allowed("bash", "-o", "pipefail", "server.sh")
        allowed("deno", "run", "--allow-net", "server.ts")
        allowed("docker", "run", "-i", "--rm", "-v", "/srv/docs:/docs:ro", "mcp/filesystem")
        allowed("docker", "run", "--privileged=false", "image")
        allowed("C:\\Tools\\server.exe")
    }

    @Test
    fun `the command is a name or an absolute path`() {
        assertContains(refused("./server"), "relative path")
        assertContains(refused("bin/server"), "relative path")
        assertContains(refused("npx -y server"), "spaces")
        assertContains(refused(" "), "empty")
    }

    @Test
    fun `a shell running code is refused`() {
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
            assertContains(refused(command, *args.toTypedArray()), "runs the c")
        }
    }

    @Test
    fun `inline code of an interpreter is refused`() {
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
            assertContains(refused(command, *args.toTypedArray()), "runs the code")
        }
    }

    @Test
    fun `a wrapping env is looked through`() {
        assertContains(refused("/usr/bin/env", "FOO=1", "bash", "-c", "x"), "bash runs the code")
        assertContains(refused("env", "-S", "bash -c x"), "env -S")
        allowed("env", "FOO=1", "npx", "server")
    }

    @Test
    fun `privileged containers are refused`() {
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
            assertContains(refused("docker", *args.toTypedArray()), why)
        }
        assertEquals(
            "podman run with --privileged is refused: it gives the container every privilege of the host.",
            refused("podman", "run", "--privileged", "image"),
        )
    }
}
