package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the connections of every transport do as the client side of JSON-RPC. */
internal object ClientSide {
    const val CANCELLED: String = "notifications/cancelled"
    const val PING: String = "ping"

    private const val NOTIFICATION_BUFFER = 64

    /** The flow of a connection's notifications, which drops the oldest when nobody keeps up. */
    fun notifications(): MutableSharedFlow<JsonRpcMessage.Notification> = MutableSharedFlow(
        extraBufferCapacity = NOTIFICATION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun cancelParams(id: Long, reason: String): JsonObject = buildJsonObject {
        put("requestId", JsonPrimitive(id))
        put("reason", reason)
    }

    /** The answer to the server's [request]: a result to a ping, an error to any other method. */
    fun reply(request: JsonRpcMessage.Request): String = if (request.method == PING) {
        JsonRpc.result(request.id, JsonObject(emptyMap()))
    } else {
        JsonRpc.error(request.id, JsonRpc.METHOD_NOT_FOUND, "The client offers no ${request.method}.")
    }
}
