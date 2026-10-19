package org.unitedsoftware.alexandrite.mcp.fake

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A protected Streamable HTTP MCP server at [url] and its authorization server at [issuer], on one loopback port.
 *
 * The MCP server offers `echo` (scope `files:read`) and `write` (scope `files:write`); the authorization server
 * registers clients dynamically when [registration], accepts the pre-registered [clientId] and checks PKCE.
 */
public class FakeOAuthServer(
    /** Whether a 401 names the resource metadata, which is otherwise found at the well-known path. */
    private val namesMetadata: Boolean = true,
    private val registration: Boolean = true,
    /** Whether the authorization server promises and sends `iss`. */
    private val issParameter: Boolean = true,
    /** Whether it accepts Client ID Metadata Documents. */
    private val clientDocuments: Boolean = false,
    private val clientId: String? = null,
    private val clientSecret: String? = null,
    private val expiresIn: Long = 3600,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val executor: ExecutorService = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    private val clients = ConcurrentHashMap<String, Client>()
    private val codes = ConcurrentHashMap<String, Code>()
    private val access = ConcurrentHashMap<String, List<String>>()
    private val refresh = ConcurrentHashMap<String, List<String>>()

    /** What happened, such as `register native http://127.0.0.1:1234/callback` or `refresh`. */
    public val events: MutableList<String> = CopyOnWriteArrayList()

    /** Every credential it issued or was sent: codes, verifiers, tokens and client secrets. */
    public val secrets: MutableList<String> = CopyOnWriteArrayList()

    /** The authorization server sends this `iss` in place of its own when set. */
    @Volatile
    public var wrongIss: String? = null

    /** Refuses every refresh with `invalid_grant` when true. */
    @Volatile
    public var refuseRefresh: Boolean = false

    public val origin: String = run {
        server.createContext("/") { exchange ->
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
        "http://127.0.0.1:${server.address.port}"
    }

    public val url: String = "$origin/mcp"
    public val issuer: String = "$origin/auth"
    public val resourceMetadata: String = "$origin/.well-known/oauth-protected-resource/mcp"

    init {
        clientId?.let { clients[it] = Client(emptyList(), clientSecret, "pre-registered") }
        clientSecret?.let { secrets += it }
    }

    /** Plays the user who approves [authorizationUrl], returning where the authorization server sends the browser. */
    public fun approve(authorizationUrl: String): String {
        val response = HTTP.send(
            HttpRequest.newBuilder(URI(authorizationUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == FOUND) { "The authorization was refused: ${response.body()}" }
        return response.headers().firstValue("location").get()
    }

    /** Revokes every access token, as a server does when it rotates its keys. */
    public fun revoke() {
        access.clear()
        events += "revoked"
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath
        when {
            path == "/mcp" -> mcp(exchange)

            path == "/.well-known/oauth-protected-resource/mcp" -> {
                events += "resource metadata"
                json(
                    exchange,
                    OK,
                    buildJsonObject {
                        put("resource", url)
                        put("authorization_servers", buildJsonArray { add(JsonPrimitive(issuer)) })
                        put("scopes_supported", buildJsonArray { add(JsonPrimitive(READ)) })
                    },
                )
            }

            path == "/.well-known/oauth-authorization-server/auth" -> json(exchange, OK, metadata())

            path == "/auth/authorize" -> authorize(exchange)

            path == "/auth/token" -> token(exchange)

            path == "/auth/register" && registration -> register(exchange)

            else -> {
                events += "missing $path"
                exchange.sendResponseHeaders(NOT_FOUND, -1)
            }
        }
    }

    private fun metadata() = buildJsonObject {
        put("issuer", issuer)
        put("authorization_endpoint", "$issuer/authorize")
        put("token_endpoint", "$issuer/token")
        if (registration) put("registration_endpoint", "$issuer/register")
        put("code_challenge_methods_supported", buildJsonArray { add(JsonPrimitive("S256")) })
        put(
            "scopes_supported",
            buildJsonArray {
                listOf(READ, WRITE, "offline_access").forEach { add(JsonPrimitive(it)) }
            },
        )
        put("authorization_response_iss_parameter_supported", issParameter)
        put("client_id_metadata_document_supported", clientDocuments)
        put("token_endpoint_auth_methods_supported", buildJsonArray { add(JsonPrimitive("client_secret_basic")) })
    }

    // The authorization server.

    private fun register(exchange: HttpExchange) {
        val body = parse(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
        val redirects = (body["redirect_uris"] as JsonArray).map { it.jsonPrimitive.content }
        val type = (body["application_type"] as? JsonPrimitive)?.content
        val method = (body["token_endpoint_auth_method"] as? JsonPrimitive)?.content
        val id = "client-${UUID.randomUUID()}"
        clients[id] = Client(redirects, null, type)
        events += "register $type $method ${redirects.joinToString(" ")}"
        json(
            exchange,
            CREATED,
            buildJsonObject {
                put("client_id", id)
                put("redirect_uris", JsonArray(redirects.map(::JsonPrimitive)))
                put("token_endpoint_auth_method", "none")
            },
        )
    }

    private fun authorize(exchange: HttpExchange) {
        val query = form(exchange.requestURI.rawQuery.orEmpty())
        val id = query["client_id"].orEmpty()
        val client = clients[id]
        val redirect = query["redirect_uri"].orEmpty()
        val document = clientDocuments && id.startsWith("https://")
        val problem = when {
            query["response_type"] != "code" -> "response_type"
            client == null && !document -> "client_id"
            client != null && client.redirects.isNotEmpty() && redirect !in client.redirects -> "redirect_uri"
            query["code_challenge_method"] != "S256" || query["code_challenge"].isNullOrEmpty() -> "pkce"
            query["resource"] != url -> "resource"
            query["state"].isNullOrEmpty() -> "state"
            else -> null
        }
        if (problem != null) {
            events += "authorize refused: $problem"
            return text(exchange, BAD_REQUEST, "Refused: $problem")
        }
        val scopes = query["scope"]?.split(' ')?.filter(String::isNotEmpty).orEmpty()
        events += "authorize ${if (document) "document " else ""}scope=${scopes.joinToString(" ")}"
        val code = "code-${UUID.randomUUID()}"
        secrets += code
        codes[code] = Code(id, redirect, query.getValue("code_challenge"), scopes)
        val iss = wrongIss ?: issuer.takeIf { issParameter }
        val location = redirect + "?code=" + encode(code) + "&state=" + encode(query.getValue("state")) +
            (iss?.let { "&iss=" + encode(it) } ?: "")
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(FOUND, -1)
    }

    private fun token(exchange: HttpExchange) {
        val body = form(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
        val basic = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Basic ")?.let {
            Base64.getDecoder().decode(it).toString(Charsets.UTF_8).split(':', limit = 2).map(::decode)
        }
        val id = basic?.first() ?: body["client_id"]
        val client = id?.let { clients[it] }
        if (client?.secret != null && basic?.getOrNull(1) != client.secret) {
            events += "token refused: client secret"
            return error(exchange, UNAUTHORIZED, "invalid_client")
        }
        if (body["resource"] != url) {
            events += "token refused: resource"
            return error(exchange, BAD_REQUEST, "invalid_target")
        }
        when (body["grant_type"]) {
            "authorization_code" -> {
                val verifier = body["code_verifier"].orEmpty()
                secrets += verifier
                val code = body["code"]?.let { codes.remove(it) }
                val problem = when {
                    code == null -> "code"
                    code.client != id -> "client"
                    code.redirect != body["redirect_uri"] -> "redirect_uri"
                    s256(verifier) != code.challenge -> "pkce"
                    else -> null
                }
                if (problem != null) {
                    events += "token refused: $problem"
                    return error(exchange, BAD_REQUEST, "invalid_grant")
                }
                events += "token pkce verified"
                grant(exchange, code!!.scopes)
            }

            "refresh_token" -> {
                val scopes = body["refresh_token"]?.let { refresh.remove(it) }
                if (scopes == null || refuseRefresh) {
                    events += "refresh refused"
                    return error(exchange, BAD_REQUEST, "invalid_grant")
                }
                events += "refresh"
                grant(exchange, scopes)
            }

            else -> error(exchange, BAD_REQUEST, "unsupported_grant_type")
        }
    }

    private fun grant(exchange: HttpExchange, scopes: List<String>) {
        val token = "access-${UUID.randomUUID()}"
        val renewal = "refresh-${UUID.randomUUID()}"
        secrets += token
        secrets += renewal
        access[token] = scopes
        refresh[renewal] = scopes
        json(
            exchange,
            OK,
            buildJsonObject {
                put("access_token", token)
                put("token_type", "Bearer")
                put("expires_in", expiresIn)
                put("refresh_token", renewal)
                put("scope", scopes.joinToString(" "))
            },
        )
    }

    // The MCP server.

    private fun mcp(exchange: HttpExchange) {
        val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        val scopes = token?.let { access[it] }
        if (scopes == null) {
            events += if (token == null) "mcp without token" else "mcp with unknown token"
            val metadata = if (namesMetadata) "resource_metadata=\"$resourceMetadata\", " else ""
            exchange.responseHeaders.add("WWW-Authenticate", "Bearer ${metadata}scope=\"$READ\"")
            return exchange.sendResponseHeaders(UNAUTHORIZED, -1)
        }
        if (exchange.requestMethod != "POST") return exchange.sendResponseHeaders(METHOD_NOT_ALLOWED, -1)
        val body = parse(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
        val id = body["id"] ?: return exchange.sendResponseHeaders(ACCEPTED, -1)
        val params = body["params"] as? JsonObject ?: JsonObject(emptyMap())
        when ((body["method"] as JsonPrimitive).content) {
            "server/discover" -> result(
                exchange,
                id,
                buildJsonObject {
                    put("supportedVersions", buildJsonArray { add(JsonPrimitive(MODERN)) })
                    put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                },
            )

            "tools/list" -> result(
                exchange.also { events += "listed" },
                id,
                buildJsonObject {
                    put(
                        "tools",
                        buildJsonArray {
                            for (name in listOf("echo", "write")) {
                                add(
                                    buildJsonObject {
                                        put("name", name)
                                        put("inputSchema", buildJsonObject { put("type", "object") })
                                    },
                                )
                            }
                        },
                    )
                },
            )

            "tools/call" -> {
                val name = (params["name"] as JsonPrimitive).content
                val needed = if (name == "write") WRITE else READ
                if (needed !in scopes) {
                    events += "insufficient scope for $name"
                    exchange.responseHeaders.add(
                        "WWW-Authenticate",
                        "Bearer error=\"insufficient_scope\", scope=\"$needed\", " +
                            "resource_metadata=\"$resourceMetadata\"",
                    )
                    return exchange.sendResponseHeaders(FORBIDDEN, -1)
                }
                val text = ((params["arguments"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content ?: name
                events += "called $name"
                result(
                    exchange,
                    id,
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    },
                                )
                            },
                        )
                    },
                )
            }

            else -> json(
                exchange,
                NOT_FOUND,
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", -32601)
                            put("message", "Method not found")
                        },
                    )
                },
            )
        }
    }

    private fun result(exchange: HttpExchange, id: JsonElement, result: JsonObject) = json(
        exchange,
        OK,
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", JsonObject(result + ("resultType" to JsonPrimitive("complete"))))
        },
    )

    private fun error(exchange: HttpExchange, status: Int, code: String) =
        json(exchange, status, buildJsonObject { put("error", code) })

    private fun json(exchange: HttpExchange, status: Int, body: JsonObject) {
        val bytes = JSON.encodeToString(JsonObject.serializer(), body).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun text(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private class Client(val redirects: List<String>, val secret: String?, val type: String?)

    private class Code(val client: String, val redirect: String, val challenge: String, val scopes: List<String>)

    public companion object {
        public const val READ: String = "files:read"
        public const val WRITE: String = "files:write"
        private const val MODERN = "2026-07-28"
        private const val OK = 200
        private const val CREATED = 201
        private const val ACCEPTED = 202
        private const val FOUND = 302
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
        private val JSON = Json { ignoreUnknownKeys = true }
        private val HTTP: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

        /** The status and text of a GET of [url], as a browser sees it. */
        public fun browse(url: String): Pair<Int, String> {
            val response = HTTP.send(
                HttpRequest.newBuilder(URI(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            return response.statusCode() to response.body()
        }

        private fun parse(text: String): JsonObject = JSON.parseToJsonElement(text) as JsonObject

        private fun form(text: String): Map<String, String> = text.split('&').filter { '=' in it }.associate {
            val (name, value) = it.split('=', limit = 2)
            decode(name) to decode(value)
        }

        private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)

        private fun decode(text: String) = URLDecoder.decode(text, Charsets.UTF_8)

        private fun s256(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
