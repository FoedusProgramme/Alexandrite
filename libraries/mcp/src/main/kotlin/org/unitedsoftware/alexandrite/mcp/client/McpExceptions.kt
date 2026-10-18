package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/** A failure to talk to an MCP server. */
internal open class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A JSON-RPC error the server answered with. */
internal class McpRpcException(
    val code: Int,
    val rpcMessage: String,
    val data: JsonElement?,
    /** The HTTP status that carried the error, null on stdio and for a 2xx. */
    val httpStatus: Int? = null,
) : McpException("The server answered with error $code: $rpcMessage")

/** An HTTP server that wants the client to authenticate. */
internal class McpUnauthorized(
    /** The `WWW-Authenticate` header of the response, null when it had none. */
    val wwwAuthenticate: String?,
    message: String,
) : McpException(message)

internal class McpTimeoutException(val method: String, val timeout: Duration) :
    McpException("The server did not answer $method within $timeout.")

/** A message or a behaviour that breaks the protocol. */
internal class McpProtocolException(message: String, cause: Throwable? = null) : McpException(message, cause)

/** A transport that failed or ended. */
internal class McpConnectionException(message: String, cause: Throwable? = null) : McpException(message, cause)

/** An HTTP status that carried no JSON-RPC error. */
internal class McpHttpStatusException(val status: Int, message: String, cause: Throwable? = null) :
    McpException(message, cause)

/** A legacy HTTP session the server no longer knows. */
internal class McpSessionExpired(message: String) : McpException(message)
