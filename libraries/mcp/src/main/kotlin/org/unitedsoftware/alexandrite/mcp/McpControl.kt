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
            if (server.spec.transport is Transport.Http) McpTransport.HTTP else McpTransport.STDIO,
            when (status.state) {
                ServerState.CONNECTING -> McpServerState.CONNECTING
                ServerState.CONNECTED -> McpServerState.CONNECTED
                ServerState.DOWN -> McpServerState.DOWN
                ServerState.NEEDS_LOGIN -> McpServerState.NEEDS_LOGIN
                ServerState.DISABLED -> McpServerState.DISABLED
            },
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
        hub.server(name) ?: throw McpLoginException("No MCP server is named $name.")
        failing { hub.oauth.loginTransport(name) }
    }

    private inline fun <T> failing(block: () -> T): T = try {
        block()
    } catch (e: OAuthException) {
        throw McpLoginException(e.message ?: "$e", e)
    }
}

/** An MCP server and how it is doing. */
public class McpServerView internal constructor(
    public val name: String,
    public val transport: McpTransport,
    public val state: McpServerState,
    public val tools: Int,
    public val lastError: String?,
    widerScopes: List<String>?,
) {
    /** The scopes the server last asked a wider login for, null when it asked for none. */
    public val widerScopes: List<String>? = widerScopes?.toList()
}

/** How the host reaches an MCP server. */
@JvmInline
public value class McpTransport internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** A program the host starts and speaks to over its standard streams. */
        public val STDIO: McpTransport = McpTransport("stdio")

        /** An address the host speaks to over HTTP. */
        public val HTTP: McpTransport = McpTransport("http")

        /** The values this version knows. */
        public val entries: List<McpTransport> = listOf(STDIO, HTTP)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): McpTransport = McpTransport(id)
    }
}

/** How an MCP server is doing. */
@JvmInline
public value class McpServerState internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val CONNECTING: McpServerState = McpServerState("connecting")

        public val CONNECTED: McpServerState = McpServerState("connected")

        /** The last connection failed. */
        public val DOWN: McpServerState = McpServerState("down")

        /** The server waits for the operator to log in. */
        public val NEEDS_LOGIN: McpServerState = McpServerState("needs_login")

        /** The config switches the server off. */
        public val DISABLED: McpServerState = McpServerState("disabled")

        /** The values this version knows. */
        public val entries: List<McpServerState> = listOf(CONNECTING, CONNECTED, DOWN, NEEDS_LOGIN, DISABLED)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): McpServerState = McpServerState(id)
    }
}

/** A login that waits for the browser. */
public class McpLogin internal constructor(
    public val url: String,
    /** Whether the host completes it when the browser comes back to its loopback address. */
    public val loopback: Boolean,
)

public class McpLoginException(message: String, cause: Throwable? = null) : Exception(message, cause)
