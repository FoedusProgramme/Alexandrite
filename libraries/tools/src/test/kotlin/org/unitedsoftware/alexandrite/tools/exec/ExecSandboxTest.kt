package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkLease
import org.unitedsoftware.alexandrite.sdk.sandbox.Sandbox
import org.unitedsoftware.alexandrite.sdk.tool.BackgroundCall
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.testkit.RecordingEnvironment
import org.unitedsoftware.alexandrite.testkit.TestToolContext
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisabledOnOs(OS.WINDOWS)
class ExecSandboxTest {
    @TempDir
    lateinit var directory: Path

    private val fs: ExecFixture by lazy { ExecFixture(directory) }
    private val environment = RecordingEnvironment("seatbelt")

    private fun context(sandbox: Sandbox?, clearance: ClearanceKind? = ClearanceKind.SANDBOX): TestToolContext =
        testToolContext(
            turn = operatorTurn(),
            workspace = fs.workspace,
            clearance = clearance?.let { Clearance(it) },
            sandbox = sandbox,
        )

    // The review.

    @Test
    fun `a call of an agent with a sandbox is reviewed as contained, with its directories and hosts`() {
        val sandbox = FakeSandbox(environment)
        val sub = fs.workspace.resolve("sub").also { it.toFile().mkdirs() }

        val review = fs.tool().check(
            context(sandbox, null),
            "argv" to listOf("sh", "-c", "true && cd sub && true"),
            "network" to listOf("example.com", "127.0.0.1:8080"),
        )

        val contained = assertNotNull(review.contained)
        assertEquals("seatbelt", contained.environmentName)
        assertEquals(listOf(fs.workspace, sub), contained.boundary.writable)
        assertEquals(listOf("example.com", "127.0.0.1:8080"), contained.hosts)
        assertNull(review.problem)
    }

    @Test
    fun `a call on the host, or asking to leave the sandbox, is not contained`() {
        assertNull(fs.tool().check(context(null, null), "command" to "true").contained)
        val outside = JsonObject(arguments("command" to "true") + ("sandbox" to JsonPrimitive(false)))

        assertNull(runBlocking { fs.tool().review(outside, context(FakeSandbox(environment), null)) }.contained)
    }

    @Test
    fun `a strict sandbox refuses a call that asks to leave it, and a malformed host is a problem`() {
        val outside = JsonObject(arguments("command" to "true") + ("sandbox" to JsonPrimitive(false)))

        val strict = runBlocking { fs.tool().review(outside, context(FakeSandbox(environment, strict = true), null)) }
        val wrong = fs.tool().check(
            context(FakeSandbox(environment), null),
            "command" to "true",
            "network" to listOf("a/b"),
        )

        assertEquals("Your agent runs every program in its sandbox, so 'sandbox' may not be false.", strict.problem)
        assertEquals("'a/b' is no host.", wrong.problem)
    }

    // The run.

    @Test
    fun `every step of a chain starts through the environment inside the opened boundary`() {
        val sandbox = FakeSandbox(environment)

        val result = fs.tool().run(
            context(sandbox),
            "argv" to listOf("sh", "-c", "true && printenv HTTP_PROXY"),
            "network" to listOf("example.com"),
        )

        assertEquals("Exit code: 0\n$ true\n$ printenv HTTP_PROXY\nhttp://127.0.0.1:9", result.text)
        assertEquals(2, environment.launches.size)
        assertTrue(environment.launches.all { it.boundary.network == NetworkAccess.Proxy(9) })
        assertEquals(listOf(listOf("example.com")), sandbox.opened)
        assertEquals(1, sandbox.closed)
    }

    @Test
    fun `a call that leaves the sandbox runs on the host`() {
        val sandbox = FakeSandbox(environment)
        val outside = JsonObject(arguments("command" to "true") + ("sandbox" to JsonPrimitive(false)))

        val result = runBlocking { fs.tool().execute(outside, context(sandbox, ClearanceKind.HUMAN)) }

        assertEquals("Exit code: 0\n[no output]", result.text)
        assertEquals(emptyList(), environment.launches)
        assertEquals(emptyList(), sandbox.opened)
    }

    @Test
    fun `the result names the hosts the proxy refused and the bounds a failed run had`() {
        val refused = fs.tool().run(
            context(FakeSandbox(environment, refused = listOf("example.com"))),
            "command" to "true",
        )
        val failed = fs.tool().run(context(FakeSandbox(environment)), "command" to "false")
        val strict = fs.tool().run(context(FakeSandbox(environment, strict = true)), "command" to "false")

        assertContains(
            refused.text,
            "[The sandbox's proxy refused example.com: call again with \"network\": [\"example.com\"] to ask for them.]",
        )
        assertContains(failed.text, "programs that ignore HTTP_PROXY, such as ssh or database clients, cannot use.")
        assertContains(failed.text, "call again with \"sandbox\": false.]")
        assertFalse("\"sandbox\": false" in strict.text)
        assertFalse("sandbox" in fs.tool().run(context(null, ClearanceKind.HUMAN), "command" to "false").text)
    }

    @Test
    fun `an unvetted program's output reports untrusted content inside the sandbox as on the host`() {
        val sandboxed = context(FakeSandbox(environment))
        val host = context(null, ClearanceKind.HUMAN)

        fs.tool().run(sandboxed, "command" to "true")
        fs.tool().run(host, "command" to "true")

        assertEquals(host.untrustedReasons, sandboxed.untrustedReasons)
        assertEquals(1, sandboxed.untrustedReasons.size)
    }

    @Test
    fun `a monitor runs its program through the environment`() {
        val updates = CopyOnWriteArrayList<ToolResult>()
        val context = object : ToolContext by context(FakeSandbox(environment)) {
            override suspend fun background(): BackgroundCall = object : BackgroundCall {
                override suspend fun update(result: ToolResult): Boolean = updates.add(result)
            }
        }
        val arguments = JsonObject(
            arguments("argv" to listOf("sh", "-c", "echo MATCH one")) + (
                "monitor" to buildJsonObject {
                    put("filter", "MATCH")
                    put("batchSeconds", 1)
                    put("intervalSeconds", 1)
                }
                ),
        )

        val result = runBlocking { fs.tool().execute(arguments, context) }

        assertTrue((updates.map { it.text } + result.text).any { "MATCH one" in it }, result.text)
        assertEquals(listOf("echo"), environment.launches.map { it.command.first().substringAfterLast('/') })
    }

    /** A sandbox of [environment] whose boundary is the call's directories and whose proxy refuses [refused]. */
    private class FakeSandbox(
        override val environment: ExecutionEnvironment,
        override val strict: Boolean = false,
        private val refused: List<String> = emptyList(),
    ) : Sandbox {
        val opened = CopyOnWriteArrayList<List<String>>()

        @Volatile
        var closed = 0

        override fun boundary(directories: List<Path>): Boundary = Boundary(writable = directories)

        override fun hostProblem(hosts: List<String>): String? =
            hosts.firstOrNull { '/' in it }?.let { "'$it' is no host." }

        override suspend fun open(boundary: Boundary, hosts: List<String>): NetworkLease {
            opened += hosts
            val sandbox = this
            return object : NetworkLease {
                override val boundary = Boundary(
                    boundary.writable,
                    network = NetworkAccess.Proxy(9),
                    environment = mapOf("HTTP_PROXY" to "http://127.0.0.1:9"),
                )
                override val refused: List<String> = sandbox.refused

                override fun close() {
                    sandbox.closed++
                }
            }
        }
    }
}
