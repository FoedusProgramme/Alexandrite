package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/** One transport to one MCP server, which carries JSON-RPC requests and notifications. */
internal interface McpConnection {
    /** The notifications the server sends, whatever stream carries them. */
    val notifications: SharedFlow<JsonRpcMessage.Notification>

    /** Completes with the reason once the connection ended. */
    val ended: Deferred<McpException>

    /** The protocol version requests carry outside their body, null before a legacy handshake. */
    var protocolVersion: String?

    /** Whether requests to a current-revision server mirror tool parameters into headers. */
    val mirrorsParams: Boolean

    /** Whether [listenLegacy] can open a stream at all. */
    val listensLegacy: Boolean

    /**
     * The result of the request [method] with [params], [meta] merged into its `_meta`.
     *
     * Cancelling the call abandons the request in the way of the transport; [timeout] bounds the wait for the response.
     */
    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        meta: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration? = null,
    ): JsonObject

    suspend fun notify(method: String, params: JsonObject = JsonObject(emptyMap()))

    /**
     * Receives what a legacy server sends outside any request until that stream ends.
     *
     * False when the transport or the server offers no such stream.
     */
    suspend fun listenLegacy(): Boolean

    /** Ends the connection and what it started. */
    suspend fun close()
}
