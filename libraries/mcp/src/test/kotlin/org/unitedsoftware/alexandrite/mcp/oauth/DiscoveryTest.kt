package org.unitedsoftware.alexandrite.mcp.oauth

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTest {
    @Test
    fun `a Bearer challenge is read among other schemes`() {
        val challenge = Challenge.parse(
            """Basic realm="x, y", Bearer resource_metadata="https://mcp.example.test/meta", """ +
                """scope="files:read  files:list", error_description="say \"hi\""""",
        )!!
        assertEquals("https://mcp.example.test/meta", challenge.resourceMetadata)
        assertEquals(listOf("files:read", "files:list"), challenge.scopes)
        assertNull(challenge.error)

        val narrow = Challenge.parse("""Bearer error="insufficient_scope", scope=files:write""")!!
        assertTrue(narrow.insufficientScope)
        assertEquals(listOf("files:write"), narrow.scopes)
        assertEquals(emptyList(), Challenge.parse("Bearer")!!.scopes)
        assertNull(Challenge.parse("""Basic realm="mcp""""))
        assertNull(Challenge.parse(null))
    }

    @Test
    fun `well-known addresses come in the order MCP tries them`() {
        assertEquals(
            listOf(
                "https://mcp.example.test/.well-known/oauth-protected-resource/public/mcp",
                "https://mcp.example.test/.well-known/oauth-protected-resource",
            ),
            Discovery.wellKnown(URI("https://MCP.example.test/public/mcp/"), "oauth-protected-resource", true)
                .map(URI::toString),
        )
        assertEquals(
            listOf("https://mcp.example.test/.well-known/oauth-protected-resource"),
            Discovery.wellKnown(URI("https://mcp.example.test/"), "oauth-protected-resource", true).map(URI::toString),
        )
        assertEquals(
            listOf(
                "https://auth.example.test/.well-known/oauth-authorization-server/tenant1",
                "https://auth.example.test/.well-known/openid-configuration/tenant1",
                "https://auth.example.test/tenant1/.well-known/openid-configuration",
            ),
            Discovery.wellKnown(URI("https://auth.example.test/tenant1"), "oauth-authorization-server", false)
                .map(URI::toString),
        )
        assertEquals(
            listOf(
                "https://auth.example.test:8443/.well-known/oauth-authorization-server",
                "https://auth.example.test:8443/.well-known/openid-configuration",
            ),
            Discovery.wellKnown(URI("https://auth.example.test:8443"), "oauth-authorization-server", false)
                .map(URI::toString),
        )
    }

    @Test
    fun `endpoints use https unless they are on the loopback interface`() {
        assertEquals(URI("http://127.0.0.1:8080/token"), secureUri("http://127.0.0.1:8080/token", "token endpoint"))
        assertEquals(URI("http://localhost/token"), secureUri("http://localhost/token", "token endpoint"))
        val error = assertFailsWith<OAuthException> { secureUri("http://auth.example.test/token", "token endpoint") }
        assertEquals("The token endpoint 'http://auth.example.test/token' does not use https.", error.message)
    }

    @Test
    fun `the S256 challenge is the hash of the verifier`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").challenge,
        )
        assertEquals(43, randomToken().length)
    }
}
