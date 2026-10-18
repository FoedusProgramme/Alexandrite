package org.unitedsoftware.alexandrite.mcp.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A request the fake server received. */
internal class Received(val method: String, val headers: Map<String, String>, val body: JsonObject?) {
    fun header(name: String): String? = headers[name.lowercase()]

    val rpcMethod: String? get() = body?.string("method")
}

/**
 * A Streamable HTTP MCP server on the loopback interface: a modern one, or a legacy one with sessions when [legacy].
 *
 * [sse] answers requests with event streams, and [token] makes it demand that bearer token.
 */
internal class FakeHttpServer(
    private val legacy: Boolean = false,
    private val sse: Boolean = false,
    private val token: String? = null,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val executor: ExecutorService = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    private val sessions = AtomicInteger()
    private val streams = CopyOnWriteArrayList<OutputStream>()
    private val generation = AtomicInteger()
    private val parked = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    @Volatile
    private var session: String? = null

    val received = CopyOnWriteArrayList<Received>()
    val events = Lines()

    val url: URI = run {
        server.createContext("/mcp") { exchange ->
            try {
                handle(exchange)
            } catch (e: IOException) {
                // The client went away.
            } finally {
                exchange.close()
            }
        }
        server.executor = executor
        server.start()
        URI("http://127.0.0.1:${server.address.port}/mcp")
    }

    /** Forgets the legacy session, so that the next request with it gets 404. */
    fun expire() {
        session = null
    }

    /** Ends every open listening stream. */
    fun dropStreams() {
        streams.forEach { runCatching { it.close() } }
        streams.clear()
    }

    override fun close() {
        dropStreams()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.first() }
        val text = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val body = text.takeIf { it.isNotBlank() }?.let { FakeJson.parse(it) }
        val request = Received(exchange.requestMethod, headers, body)
        received += request
        if (token != null && headers["authorization"] != "Bearer $token") {
            exchange.responseHeaders.add("WWW-Authenticate", "Bearer resource_metadata=\"https://example.test/meta\"")
            return exchange.sendResponseHeaders(401, -1)
        }
        if (legacy) legacy(exchange, request) else modern(exchange, request)
    }

    // The modern revision.

    private fun modern(exchange: HttpExchange, request: Received) {
        if (request.method != "POST") return exchange.sendResponseHeaders(405, -1)
        val body = request.body!!
        val id = body["id"] ?: return exchange.sendResponseHeaders(202, -1)
        val method = body.string("method")!!
        val params = body["params"] as? JsonObject ?: JsonObject(emptyMap())
        val version = (params["_meta"] as? JsonObject)?.string(McpVersions.META_VERSION)
        val name = params.string("name")
        val mismatch = when {
            request.header("mcp-protocol-version") != version -> "MCP-Protocol-Version"
            request.header("mcp-method") != method -> "Mcp-Method"
            method == "tools/call" && decode(request.header("mcp-name")) != name -> "Mcp-Name"
            else -> null
        }
        if (mismatch != null) return error(exchange, 400, id, McpVersions.HEADER_MISMATCH, "Header mismatch: $mismatch")
        if (version != McpVersions.MODERN) {
            return send(
                exchange,
                400,
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", McpVersions.UNSUPPORTED_VERSION)
                            put("message", "Unsupported protocol version")
                            put(
                                "data",
                                buildJsonObject {
                                    put("supported", buildJsonArray { add(JsonPrimitive(McpVersions.MODERN)) })
                                },
                            )
                        },
                    )
                },
            )
        }
        when (method) {
            "server/discover" -> answer(
                exchange,
                id,
                buildJsonObject {
                    put("resultType", "complete")
                    put("supportedVersions", buildJsonArray { add(JsonPrimitive(McpVersions.MODERN)) })
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", true) }) })
                    put(
                        "_meta",
                        buildJsonObject {
                            put(
                                McpVersions.META_SERVER,
                                buildJsonObject {
                                    put("name", "fake-http")
                                    put("version", "2.0")
                                },
                            )
                        },
                    )
                },
            )

            "subscriptions/listen" -> {
                events.add("listen ${(params["notifications"] as? JsonObject)?.get("toolsListChanged")}")
                val stream = openStream(exchange)
                write(
                    stream,
                    notification(
                        McpClient.ACKNOWLEDGED,
                        buildJsonObject {
                            put("_meta", buildJsonObject { put("io.modelcontextprotocol/subscriptionId", id) })
                            put("notifications", buildJsonObject { put("toolsListChanged", true) })
                        },
                    ),
                )
                hold(stream)
            }

            else -> common(exchange, id, method, params, request)
        }
    }

    // The legacy revisions.

    private fun legacy(exchange: HttpExchange, request: Received) {
        val given = request.header("mcp-session-id")
        when (request.method) {
            "DELETE" -> {
                events.add("deleted $given")
                return exchange.sendResponseHeaders(200, -1)
            }

            "GET" -> {
                if (given == null || given != session) return exchange.sendResponseHeaders(404, -1)
                val resumed = request.header("last-event-id")?.let { parked.remove(it) }
                if (resumed != null) {
                    events.add("resumed")
                    return write(openStream(exchange), resumed, eventId = "r2")
                }
                val stream = openStream(exchange)
                write(
                    stream,
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", "srv-ping")
                        put("method", "ping")
                    },
                )
                hold(stream)
                return
            }
        }
        val body = request.body!!
        val method = body.string("method")
        if (method == McpHttpConnection.INITIALIZE) {
            val id = "session-${sessions.incrementAndGet()}"
            session = id
            events.add("initialize ${request.header("mcp-protocol-version")}")
            exchange.responseHeaders.add("Mcp-Session-Id", id)
            return answer(
                exchange,
                body["id"]!!,
                buildJsonObject {
                    put("protocolVersion", "2025-11-25")
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", true) }) })
                    put(
                        "serverInfo",
                        buildJsonObject {
                            put("name", "fake-http-legacy")
                            put("version", "1.0")
                        },
                    )
                },
            )
        }
        if (given == null) {
            return error(exchange, 400, JsonNull, -32000, "Bad Request: No valid session ID provided")
        }
        if (given != session) return exchange.sendResponseHeaders(404, -1)
        if (request.header("mcp-protocol-version") != "2025-11-25") {
            return error(exchange, 400, body["id"] ?: JsonNull, -32600, "Bad protocol version header")
        }
        if (method == null) {
            if (body.string("id") == "srv-ping") events.add("pong")
            return exchange.sendResponseHeaders(202, -1)
        }
        val id = body["id"]
        val params = body["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (id == null) {
            if (method ==
                "notifications/cancelled"
            ) {
                events.add("cancelled ${(params["requestId"] as JsonPrimitive).content}")
            }
            if (method == McpClient.INITIALIZED) events.add("initialized")
            return exchange.sendResponseHeaders(202, -1)
        }
        if ("_meta" in params) events.add("unexpected meta")
        common(exchange, id, method, params, request)
    }

    // Both.

    private fun common(exchange: HttpExchange, id: JsonElement, method: String, params: JsonObject, request: Received) {
        when (method) {
            "tools/list" -> {
                val cursor = params.string("cursor")
                answer(
                    exchange,
                    id,
                    buildJsonObject {
                        val names = if (cursor ==
                            null
                        ) {
                            listOf("echo", "héllo")
                        } else {
                            listOf("hang", "change", "regional", "broken")
                        }
                        put("tools", buildJsonArray { names.forEach { add(tool(it)) } })
                        if (cursor == null) put("nextCursor", "page-2")
                        put("ttlMs", 5000)
                    },
                )
            }

            "tools/call" -> when (val name = params.string("name")) {
                "echo", "héllo" -> answer(
                    exchange,
                    id,
                    text((params["arguments"] as JsonObject).string("text") ?: name),
                )

                "regional" -> answer(exchange, id, text(request.header("mcp-param-region") ?: "none"))

                "change" -> {
                    generation.incrementAndGet()
                    answer(exchange, id, text("changed"))
                    streams.forEach { write(it, notification(McpClient.TOOLS_LIST_CHANGED, JsonObject(emptyMap()))) }
                }

                "resume" -> {
                    parked["r1"] = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", text("resumed"))
                    }
                    val stream = openStream(exchange)
                    stream.write("retry: 50\nid: r1\ndata:\n\n".toByteArray())
                    stream.flush()
                }

                "hang" -> {
                    val stream = openStream(exchange)
                    events.add("hanging on ${(id as JsonPrimitive).content}")
                    try {
                        while (true) {
                            stream.write(":\n\n".toByteArray())
                            stream.flush()
                            Thread.sleep(50)
                        }
                    } catch (e: IOException) {
                        events.add("abandoned ${id.content}")
                    }
                }

                else -> error(exchange, 200, id, -32602, "Unknown tool: $name")
            }

            else -> error(exchange, 404, id, -32601, "Method not found")
        }
    }

    private fun tool(name: String) = buildJsonObject {
        put("name", name)
        put(
            "inputSchema",
            when (name) {
                "regional" -> FakeJson.parse(
                    """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}}}""",
                )

                "broken" -> FakeJson.parse(
                    """{"type":"object","properties":{"list":{"type":"array","items":{"type":"string","x-mcp-header":"Item"}}}}""",
                )

                else -> FakeJson.parse("""{"type":"object"}""")
            },
        )
    }

    private fun text(value: String) = buildJsonObject {
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", value)
                    },
                )
            },
        )
    }

    private fun answer(exchange: HttpExchange, id: JsonElement, result: JsonObject) {
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", if (legacy) result else JsonObject(result + ("resultType" to JsonPrimitive("complete"))))
        }
        if (!sse) return send(exchange, 200, message)
        val stream = openStream(exchange)
        write(stream, notification("notifications/progress", buildJsonObject { put("progress", 1) }), eventId = "e1")
        write(stream, message, eventId = "e2")
    }

    private fun error(exchange: HttpExchange, status: Int, id: JsonElement, code: Int, message: String) = send(
        exchange,
        status,
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        },
    )

    private fun send(exchange: HttpExchange, status: Int, message: JsonObject) {
        val bytes = FakeJson.json.encodeToString(JsonObject.serializer(), message).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun openStream(exchange: HttpExchange): OutputStream {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        return exchange.responseBody
    }

    /** Keeps [stream] open for notifications until it breaks. */
    private fun hold(stream: OutputStream) {
        streams += stream
        try {
            while (stream in streams) {
                synchronized(stream) {
                    stream.write(":\n\n".toByteArray())
                    stream.flush()
                }
                Thread.sleep(100)
            }
        } catch (e: IOException) {
            // The client went away.
        } finally {
            streams -= stream
        }
    }

    private fun write(stream: OutputStream, message: JsonObject, eventId: String? = null) {
        val data = FakeJson.json.encodeToString(JsonObject.serializer(), message)
        try {
            synchronized(stream) {
                eventId?.let { stream.write("id: $it\n".toByteArray()) }
                stream.write("data: $data\n\n".toByteArray())
                stream.flush()
            }
        } catch (e: IOException) {
            streams -= stream
        }
    }

    private fun notification(method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
    }

    private fun decode(value: String?): String? {
        if (value == null || !value.startsWith("=?base64?") || !value.endsWith("?=")) return value
        return Base64.getDecoder().decode(value.removePrefix("=?base64?").removeSuffix("?=")).toString(Charsets.UTF_8)
    }
}
