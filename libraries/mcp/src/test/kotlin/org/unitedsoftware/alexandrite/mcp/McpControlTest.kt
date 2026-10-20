package org.unitedsoftware.alexandrite.mcp

import org.unitedsoftware.alexandrite.mcp.fake.FakeOAuthServer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpControlTest {
    @Test
    fun `the host sees each server's transport, state and tools`() = withHub(
        "files" to fake(),
        "off" to fake { enabled = false },
    ) {
        connected("files")

        val servers = McpControl(hub).servers()

        assertEquals(listOf("files", "off"), servers.map { it.name })
        assertEquals(listOf(McpTransport.STDIO, McpTransport.STDIO), servers.map { it.transport })
        assertEquals(listOf(McpServerState.CONNECTED, McpServerState.DISABLED), servers.map { it.state })
        assertEquals(8, servers.first().tools)
    }

    @Test
    fun `the host logs in with the address pasted back and logs out again`() = FakeOAuthServer().use { fake ->
        withHub(McpConfig(mapOf("fake" to ServerConfigBuilder(url = fake.url).build()))) {
            until("waiting for a login") { server("fake").status.value.state == ServerState.NEEDS_LOGIN }
            val control = McpControl(hub)
            assertEquals(McpServerState.NEEDS_LOGIN, control.servers().first().state)

            val login = control.login("fake")
            control.completeLogin("fake", fake.approve(login.url))
            until("connected") { server("fake").status.value.state == ServerState.CONNECTED }

            assertTrue(login.url.startsWith(fake.url.substringBefore("/mcp")), login.url)
            assertTrue(control.logout("fake"))
            assertFalse(control.logout("fake"))
        }
    }

    @Test
    fun `a login to a command or an unknown server is refused`() = withHub("files" to fake { enabled = false }) {
        val control = McpControl(hub)

        val command = assertFailsWith<McpLoginException> { control.login("files") }
        val unknown = assertFailsWith<McpLoginException> { control.logout("nope") }

        assertContains(command.message.orEmpty(), "not reached over HTTP")
        assertEquals("No MCP server is named nope.", unknown.message)
    }
}
