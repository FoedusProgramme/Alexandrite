package org.foedusprogramme.alexandrite.mcp.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

/**
 * A stdio MCP server run as a real child process by the tests, in the mode its first argument names: `modern`,
 * `legacy`, `legacy-old`, `silent-legacy`, `unsupported`, `ancient`, `crash`, `noisy` or `stubborn`.
 */
fun main(args: Array<String>) {
    FakeStdioServer(args.firstOrNull() ?: "modern").run()
}

private class FakeStdioServer(private val mode: String) {
    private val legacy = mode in setOf("legacy", "legacy-old", "silent-legacy", "ancient", "stubborn")
    private var initialized = false
    private var listenId: JsonElement? = null
    private var generation = 0

    fun run() {
        log("started $mode")
        if (mode == "noisy") println("this is no JSON")
        if (mode == "stubborn") {
            val child = ProcessBuilder("/bin/sleep", "300").start()
            log("child ${child.pid()}")
        }
        while (true) {
            val line = readlnOrNull() ?: break
            if (line.isBlank()) continue
            if (mode == "crash") {
                log("crashing")
                exitProcess(3)
            }
            handle(FakeJson.parse(line))
        }
        log("stdin closed")
        if (mode == "stubborn") {
            Thread.sleep(300_000)
        }
    }

    private fun handle(message: JsonObject) {
        val method = message.string("method")
        val id = message["id"]
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (method == null) {
            if (id?.let { (it as? JsonPrimitive)?.content } == "srv-1" && "result" in message) log("pong")
            return
        }
        if (id == null) return notified(method, params)
        if (legacy) legacyRequest(id, method, params) else modernRequest(id, method, params)
    }

    private fun notified(method: String, params: JsonObject) {
        when (method) {
            "notifications/initialized" -> {
                initialized = true
                send(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", "srv-1")
                        put("method", "ping")
                    },
                )
            }

            "notifications/cancelled" -> {
                val requestId = (params["requestId"] as? JsonPrimitive)?.content
                if (listenId != null && requestId == (listenId as JsonPrimitive).content) {
                    log("unsubscribed")
                } else {
                    log("cancelled $requestId: ${params.string("reason")}")
                }
            }
        }
    }

    private fun modernRequest(id: JsonElement, method: String, params: JsonObject) {
        val meta = params["_meta"] as? JsonObject
        if (meta?.string("io.modelcontextprotocol/protocolVersion") != "2026-07-28" ||
            meta["io.modelcontextprotocol/clientCapabilities"] !is JsonObject
        ) {
            return error(id, -32602, "Missing protocol metadata")
        }
        if (mode == "unsupported") {
            return send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", -32022)
                            put("message", "Unsupported protocol version")
                            put(
                                "data",
                                buildJsonObject {
                                    put("supported", buildJsonArray { add(JsonPrimitive("2027-01-01")) })
                                    put("requested", "2026-07-28")
                                },
                            )
                        },
                    )
                },
            )
        }
        when (method) {
            "server/discover" -> result(
                id,
                buildJsonObject {
                    put("supportedVersions", buildJsonArray { add(JsonPrimitive("2026-07-28")) })
                    put("capabilities", capabilities())
                    put(
                        "_meta",
                        buildJsonObject {
                            put(
                                "io.modelcontextprotocol/serverInfo",
                                buildJsonObject {
                                    put("name", "fake-modern")
                                    put("version", "1.0")
                                },
                            )
                        },
                    )
                    put("instructions", "Use the echo tool.")
                },
            )

            "subscriptions/listen" -> {
                listenId = id
                log("subscribed")
                val wanted = (params["notifications"] as? JsonObject)?.get("toolsListChanged")
                send(
                    notification(
                        "notifications/subscriptions/acknowledged",
                        buildJsonObject {
                            put("_meta", buildJsonObject { put("io.modelcontextprotocol/subscriptionId", id) })
                            put(
                                "notifications",
                                buildJsonObject {
                                    put(
                                        "toolsListChanged",
                                        wanted ?: JsonPrimitive(false),
                                    )
                                },
                            )
                        },
                    ),
                )
            }

            else -> common(id, method, params)
        }
    }

    private fun legacyRequest(id: JsonElement, method: String, params: JsonObject) {
        if (method == "server/discover" && mode == "silent-legacy") return
        if (method == "initialize") {
            val asked = params.string("protocolVersion")
            val answer = when (mode) {
                "legacy-old" -> "2025-06-18"
                "ancient" -> "2024-11-05"
                else -> if (asked in setOf("2025-11-25", "2025-06-18")) asked!! else "2025-06-18"
            }
            log("initialize asked $asked")
            return result(
                id,
                buildJsonObject {
                    put("protocolVersion", answer)
                    put("capabilities", capabilities())
                    put(
                        "serverInfo",
                        buildJsonObject {
                            put("name", "fake-legacy")
                            put("version", "0.9")
                        },
                    )
                },
                complete = false,
            )
        }
        if (!initialized) return error(id, -32601, "Method not found")
        if ("_meta" in params) log("unexpected meta on $method")
        common(id, method, params)
    }

    private fun common(id: JsonElement, method: String, params: JsonObject) {
        when (method) {
            "tools/list" -> toolsList(id, params.string("cursor"))

            "tools/call" -> call(
                id,
                params.string("name"),
                params["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
            )

            else -> error(id, -32601, "Method not found")
        }
    }

    private fun toolsList(id: JsonElement, cursor: String?) {
        val first = listOf("echo", "kinds", "fail")
        val second =
            listOf("ask", "env", "hang", "change", "exit") + if (generation > 0) listOf("added") else emptyList()
        result(
            id,
            buildJsonObject {
                put("tools", buildJsonArray { (if (cursor == null) first else second).forEach { add(tool(it)) } })
                if (cursor == null) put("nextCursor", "")
                put("ttlMs", if (cursor == null) 300_000 else 60_000)
            },
            complete = !legacy,
        )
    }

    private fun call(id: JsonElement, name: String?, arguments: JsonObject) {
        when (name) {
            "echo" -> result(id, text(arguments.string("text") ?: ""), complete = !legacy)

            "kinds" -> result(
                id,
                buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(part("text") { put("text", "hello") })
                            add(
                                part("image") {
                                    put("data", "aW1n")
                                    put("mimeType", "image/png")
                                },
                            )
                            add(
                                part("audio") {
                                    put("data", "YXVk")
                                    put("mimeType", "audio/wav")
                                },
                            )
                            add(
                                part("resource_link") {
                                    put("uri", "file:///a.txt")
                                    put("name", "a.txt")
                                    put("description", "A file")
                                    put("mimeType", "text/plain")
                                },
                            )
                            add(
                                part("resource") {
                                    put(
                                        "resource",
                                        buildJsonObject {
                                            put("uri", "file:///b.txt")
                                            put("mimeType", "text/plain")
                                            put("text", "bee")
                                        },
                                    )
                                },
                            )
                            add(part("hologram") {})
                        },
                    )
                    put("structuredContent", buildJsonObject { put("answer", 42) })
                },
                complete = !legacy,
            )

            "fail" -> result(
                id,
                buildJsonObject {
                    put("content", buildJsonArray { add(part("text") { put("text", "it broke") }) })
                    put("isError", true)
                },
            )

            "ask" -> send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "result",
                        buildJsonObject {
                            put("resultType", "input_required")
                            put(
                                "inputRequests",
                                buildJsonObject {
                                    put(
                                        "login",
                                        buildJsonObject {
                                            put("method", "elicitation/create")
                                            put("params", buildJsonObject { put("message", "Your name?") })
                                        },
                                    )
                                },
                            )
                            put("requestState", "opaque-state")
                        },
                    )
                },
            )

            "env" -> result(
                id,
                buildJsonObject {
                    put("content", buildJsonArray {})
                    put("structuredContent", JsonObject(System.getenv().mapValues { JsonPrimitive(it.value) }))
                },
            )

            "hang" -> log("hanging on ${(id as JsonPrimitive).content}")

            "change" -> {
                generation++
                if (!legacy && listenId == null) return result(id, text("changed unheard"))
                val meta = listenId?.let { buildJsonObject { put("io.modelcontextprotocol/subscriptionId", it) } }
                send(
                    notification(
                        "notifications/tools/list_changed",
                        buildJsonObject { meta?.let { put("_meta", it) } },
                    ),
                )
                result(id, text("changed"))
            }

            "exit" -> {
                log("exiting")
                exitProcess(4)
            }

            else -> error(id, -32602, "Unknown tool: $name")
        }
    }

    private fun capabilities() = buildJsonObject {
        put("tools", buildJsonObject { put("listChanged", true) })
    }

    private fun tool(name: String) = buildJsonObject {
        put("name", name)
        put("description", "The $name tool")
        put(
            "inputSchema",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
            },
        )
        if (name == "echo") put("annotations", buildJsonObject { put("readOnlyHint", true) })
    }

    private fun text(value: String) = buildJsonObject {
        put("content", buildJsonArray { add(part("text") { put("text", value) }) })
    }

    private fun part(type: String, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", type)
        fill()
    }

    private fun result(id: JsonElement, result: JsonObject, complete: Boolean = true) = send(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", if (complete) JsonObject(result + ("resultType" to JsonPrimitive("complete"))) else result)
        },
    )

    private fun error(id: JsonElement, code: Int, message: String) = send(
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

    private fun notification(method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
    }

    @Synchronized
    private fun send(message: JsonObject) {
        println(FakeJson.json.encodeToString(JsonObject.serializer(), message))
        System.out.flush()
    }

    private fun log(text: String) {
        System.err.println(text)
        System.err.flush()
    }
}

internal object FakeJson {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun parse(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject
}
