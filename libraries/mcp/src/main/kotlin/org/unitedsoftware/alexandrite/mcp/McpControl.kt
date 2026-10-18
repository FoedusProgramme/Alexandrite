package org.unitedsoftware.alexandrite.mcp

import org.unitedsoftware.alexandrite.mcp.oauth.OAuthException
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi

/** The configured MCP servers as the host's operator sees and logs in to them. */
@HostApi
@Singleton
public class McpControl @Inject internal constructor(private val hub: McpHub) {
    public fun servers(): List<McpServerView> = hub.servers.map { server ->
        val status = server.status.value
        McpServerView(
            server.spec.name,
            if (server.spec.transport is Transport.Http) "http" else "stdio",
            status.state.name.lowercase(),
            status.tools,
            status.lastError,
            hub.oauth.widerScopes(server.spec.name),
        )
    }

    /** Starts a login to [server] and returns the link to open, or throws [McpLoginException]. */
    public suspend fun login(server: String): McpLogin {
        account(server)
        val start = failing { hub.oauth.start(server) }
        return McpLogin(start.url, start.loopback)
    }

    /** Completes the login to [server] with the address the browser was sent back to, or throws [McpLoginException]. */
    public suspend fun completeLogin(server: String, address: String) {
        account(server)
        failing { hub.oauth.complete(server, address) }
    }

    /** Forgets the tokens and the pending login of [server], false when it had neither. */
    public suspend fun logout(server: String): Boolean {
        account(server)
        return hub.oauth.logout(server)
    }

    private fun account(name: String) {
        val server = hub.server(name) ?: throw McpLoginException("No MCP server is named $name.")
        if (server.spec.transport !is Transport.Http) {
            throw McpLoginException("MCP server $name is not reached over HTTP, so it has no login.")
        }
    }

    private inline fun <T> failing(block: () -> T): T = try {
        block()
    } catch (e: OAuthException) {
        throw McpLoginException(e.message ?: "$e", e)
    }
}

/** An MCP server and how it is doing. */
public class McpServerView(
    public val name: String,
    /** `stdio` or `http`. */
    public val transport: String,
    /** `connecting`, `connected`, `down`, `needs_login` or `disabled`. */
    public val state: String,
    public val tools: Int,
    public val lastError: String?,
    /** The scopes the server last asked a wider login for, null when it asked for none. */
    public val widerScopes: List<String>?,
)

/** A login that waits for the browser. */
public class McpLogin(
    public val url: String,
    /** Whether the host completes it when the browser comes back to its loopback address. */
    public val loopback: Boolean,
)

public class McpLoginException(message: String, cause: Throwable? = null) : Exception(message, cause)
