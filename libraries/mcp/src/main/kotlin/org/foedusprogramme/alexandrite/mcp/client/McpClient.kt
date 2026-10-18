package org.foedusprogramme.alexandrite.mcp.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class McpClientOptions(
    /** How long a stdio server has to answer `server/discover` before it counts as legacy. */
    val probeTimeout: Duration = 10.seconds,
    /** The timeout of the handshake and of each page of `tools/list`. */
    val requestTimeout: Duration = 60.seconds,
    val reconnectMin: Duration = 1.seconds,
    val reconnectMax: Duration = 5.minutes,
)

/**
 * The tools of one MCP server over one [McpConnection], in whichever revision the server speaks.
 *
 * [connect] negotiates the revision; [close] ends the connection.
 */
internal class McpClient private constructor(
    private val connection: McpConnection,
    private val clientInfo: McpImplementation,
    private val options: McpClientOptions,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val handshake = Mutex()
    private val resubscribed = AtomicBoolean()

    @Volatile
    private var serverInfo: McpServerInfo? = null

    @Volatile
    private var paramHeaders: Map<String, List<ParamHeader>> = emptyMap()

    @Volatile
    private var acknowledged = false

    @Volatile
    private var listening: Job? = null

    /** What the server said about itself when the client connected. */
    val server: McpServerInfo get() = serverInfo ?: throw IllegalStateException("Not connected.")

    /** Fires when the server's tool list may have changed. */
    val toolsChanged: Flow<Unit> = changes

    /** Completes with the reason once the connection ended. */
    val ended: Deferred<McpException> get() = connection.ended

    private val mirrorsParams: Boolean get() = connection is McpHttpConnection && !server.legacy

    /** All tools of the server, page after page. */
    suspend fun listTools(): McpToolList {
        val tools = mutableListOf<McpTool>()
        val rejected = mutableListOf<McpRejectedTool>()
        val headers = mutableMapOf<String, List<ParamHeader>>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var ttl: Duration? = null
        var pages = 0
        do {
            if (++pages > MAX_PAGES) throw McpProtocolException("The server's tool list runs past $MAX_PAGES pages.")
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = bounded(TOOLS_LIST, options.requestTimeout) {
                call(TOOLS_LIST, params, timeout = options.requestTimeout)
            }
            if (resultType(result) != RESULT_COMPLETE) {
                throw McpProtocolException(
                    "The server answered $TOOLS_LIST with the result type ${resultType(result)}.",
                )
            }
            (result["ttlMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.milliseconds?.let { pageTtl ->
                ttl = ttl?.coerceAtMost(pageTtl) ?: pageTtl
            }
            for (element in (result["tools"] as? JsonArray).orEmpty()) {
                val json = element as? JsonObject
                val tool = json?.let(::parseTool)
                if (tool == null) {
                    rejected += McpRejectedTool("", "A tool definition has no name.")
                    continue
                }
                if (tools.any { it.name == tool.name } || rejected.any { it.name == tool.name }) {
                    rejected += McpRejectedTool(tool.name, "The server lists the name twice.")
                    continue
                }
                if (mirrorsParams) {
                    val params = McpHeaders.paramHeaders(tool.inputSchema).getOrElse {
                        rejected +=
                            McpRejectedTool(tool.name, it.message ?: "Its x-mcp-header annotations are invalid.")
                        continue
                    }
                    if (params.isNotEmpty()) headers[tool.name] = params
                }
                tools += tool
            }
            cursor = result.string("nextCursor")
            if (cursor != null && !cursors.add(cursor)) {
                throw McpProtocolException("The server's tool list repeats the cursor '$cursor'.")
            }
        } while (cursor != null)
        paramHeaders = headers
        return McpToolList(tools, ttl, rejected)
    }

    /**
     * Calls the server's tool [name] with [arguments], waiting [timeout] at most.
     *
     * A timeout or a cancellation of the caller cancels the request on the server.
     */
    suspend fun callTool(name: String, arguments: JsonObject, timeout: Duration): McpCallOutcome {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val headers = if (mirrorsParams) {
            McpHeaders.paramValues(paramHeaders[name].orEmpty(), arguments)
        } else {
            emptyMap()
        }
        val result = bounded(TOOLS_CALL, timeout) { call(TOOLS_CALL, params, headers, timeout) }
        return when (val type = resultType(result)) {
            RESULT_COMPLETE -> McpCallOutcome.Completed(
                parseContent(result["content"]),
                result["structuredContent"],
                result.boolean("isError") == true,
            )

            RESULT_INPUT_REQUIRED -> McpCallOutcome.NeedsInput(
                result["inputRequests"] as? JsonObject,
                result.string("requestState"),
            )

            else -> throw McpProtocolException("The server answered $TOOLS_CALL with the unknown result type $type.")
        }
    }

    suspend fun close() {
        scope.cancel()
        connection.close()
    }

    private suspend fun call(
        method: String,
        params: JsonObject,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration? = null,
    ): JsonObject {
        val info = server
        return try {
            connection.request(method, params, meta(info), headers, timeout)
        } catch (e: McpSessionExpired) {
            renewSession(info)
            connection.request(method, params, meta(server), headers, timeout)
        }
    }

    private fun meta(info: McpServerInfo): JsonObject? = if (info.legacy) null else modernMeta()

    private fun modernMeta(): JsonObject = buildJsonObject {
        put(McpVersions.META_VERSION, McpVersions.MODERN)
        put(McpVersions.META_CAPABILITIES, JsonObject(emptyMap()))
        put(McpVersions.META_CLIENT, clientInfo.toJson())
    }

    private suspend fun <T> bounded(method: String, timeout: Duration, block: suspend () -> T): T =
        withTimeoutOrNull(timeout) { block() } ?: throw McpTimeoutException(method, timeout)

    private suspend fun negotiate() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.notifications.collect(::notified)
        }
        connection.protocolVersion = McpVersions.MODERN
        val discovered = probe()
        if (discovered == null) {
            handshakeLegacy()
            return
        }
        val supported = (discovered["supportedVersions"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        when {
            McpVersions.MODERN in supported -> serverInfo = McpServerInfo(
                McpVersions.MODERN,
                legacy = false,
                McpImplementation.of((discovered["_meta"] as? JsonObject)?.get(McpVersions.META_SERVER)),
                discovered["capabilities"] as? JsonObject ?: JsonObject(emptyMap()),
                discovered.string("instructions"),
            )

            supported.any(McpVersions::isLegacy) -> handshakeLegacy()

            else -> throw unsupported(supported)
        }
    }

    /** The result of `server/discover`, null when the server is a legacy one. */
    private suspend fun probe(): JsonObject? {
        val stdio = connection is McpStdioConnection
        return try {
            val timeout = options.probeTimeout
            val result = withTimeoutOrNull(timeout) {
                connection.request(DISCOVER, meta = modernMeta(), timeout = timeout)
            }
            if (result == null && !stdio) throw McpTimeoutException(DISCOVER, timeout)
            result
        } catch (e: McpRpcException) {
            when {
                e.code == McpVersions.UNSUPPORTED_VERSION -> {
                    val supported = ((e.data as? JsonObject)?.get("supported") as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.content }
                    throw unsupported(supported)
                }

                McpVersions.isModernError(e.code) -> throw McpProtocolException(
                    "The server refused $DISCOVER: ${e.rpcMessage} (${e.code}).",
                    e,
                )

                else -> null
            }
        } catch (e: McpHttpStatusException) {
            if (e.status in 400..499 && e.status !in TRANSIENT_STATUSES) null else throw e
        } catch (e: McpTimeoutException) {
            if (stdio) null else throw e
        }
    }

    private suspend fun handshakeLegacy() {
        connection.protocolVersion = null
        val params = buildJsonObject {
            put("protocolVersion", McpVersions.LEGACY.first())
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", clientInfo.toJson())
        }
        val timeout = options.requestTimeout
        val result = bounded(McpHttpConnection.INITIALIZE, timeout) {
            connection.request(McpHttpConnection.INITIALIZE, params, timeout = timeout)
        }
        val version = result.string("protocolVersion")
        if (!McpVersions.isLegacy(version)) {
            throw McpProtocolException(
                "The server speaks protocol version $version; this client speaks " +
                    (listOf(McpVersions.MODERN) + McpVersions.LEGACY).joinToString() + ".",
            )
        }
        connection.protocolVersion = version
        connection.notify(INITIALIZED)
        serverInfo = McpServerInfo(
            version!!,
            legacy = true,
            McpImplementation.of(result["serverInfo"]),
            result["capabilities"] as? JsonObject ?: JsonObject(emptyMap()),
            result.string("instructions"),
        )
    }

    /** Starts a new legacy session unless another caller did since [stale] was current. */
    private suspend fun renewSession(stale: McpServerInfo) {
        handshake.withLock {
            if (serverInfo === stale) handshakeLegacy()
        }
    }

    private fun unsupported(supported: List<String>) = McpProtocolException(
        "The server speaks protocol versions ${supported.joinToString().ifEmpty {
            "none named"
        }}; this client speaks " +
            (listOf(McpVersions.MODERN) + McpVersions.LEGACY).joinToString() + ".",
    )

    private fun notified(notification: JsonRpcMessage.Notification) {
        when (notification.method) {
            TOOLS_LIST_CHANGED -> changes.tryEmit(Unit)

            ACKNOWLEDGED -> {
                val agreed =
                    ((notification.params["notifications"] as? JsonObject)?.get("toolsListChanged") as? JsonPrimitive)
                        ?.booleanOrNull == true
                if (!agreed) {
                    listening?.cancel()
                    return
                }
                acknowledged = true
                if (resubscribed.getAndSet(false)) changes.tryEmit(Unit)
            }
        }
    }

    private fun watch() {
        val info = server
        if (!info.toolsListChanged) return
        listening = when {
            !info.legacy -> scope.launch { keepListening { listen() } }
            connection is McpHttpConnection -> scope.launch { keepListening { listenLegacy() } }
            else -> null
        }
    }

    /** Runs [open] again and again with a growing pause, until it returns false or the connection ends. */
    private suspend fun keepListening(open: suspend () -> Boolean) {
        var pause = options.reconnectMin
        var first = true
        while (scope.isActive && !connection.ended.isCompleted) {
            acknowledged = false
            if (!first) resubscribed.set(true)
            first = false
            val again = try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpRpcException) {
                false
            } catch (e: McpException) {
                true
            }
            if (!again || connection.ended.isCompleted) return
            pause = if (acknowledged) options.reconnectMin else (pause * 2).coerceAtMost(options.reconnectMax)
            delay(pause)
        }
    }

    private suspend fun listen(): Boolean {
        val params = buildJsonObject {
            put("notifications", buildJsonObject { put("toolsListChanged", true) })
        }
        connection.request(SUBSCRIBE, params, modernMeta())
        return true
    }

    private suspend fun listenLegacy(): Boolean {
        val info = server
        if (resubscribed.getAndSet(false)) changes.tryEmit(Unit)
        return try {
            connection.listenLegacy().also { if (it) acknowledged = true }
        } catch (e: McpSessionExpired) {
            renewSession(info)
            true
        }
    }

    companion object {
        const val DISCOVER: String = "server/discover"
        const val INITIALIZED: String = "notifications/initialized"
        const val TOOLS_LIST: String = "tools/list"
        const val TOOLS_CALL: String = "tools/call"
        const val TOOLS_LIST_CHANGED: String = "notifications/tools/list_changed"
        const val SUBSCRIBE: String = "subscriptions/listen"
        const val ACKNOWLEDGED: String = "notifications/subscriptions/acknowledged"

        private const val MAX_PAGES = 1000

        /** The client errors that say nothing about the revision a server speaks. */
        private val TRANSIENT_STATUSES = setOf(408, 429)

        /**
         * A client of the server behind [connection], which speaks the newest revision both know.
         *
         * Closes [connection] when it fails.
         */
        suspend fun connect(
            connection: McpConnection,
            clientInfo: McpImplementation,
            options: McpClientOptions = McpClientOptions(),
        ): McpClient {
            val client = McpClient(connection, clientInfo, options)
            try {
                client.negotiate()
                client.watch()
            } catch (e: Throwable) {
                client.close()
                throw e
            }
            return client
        }
    }
}
