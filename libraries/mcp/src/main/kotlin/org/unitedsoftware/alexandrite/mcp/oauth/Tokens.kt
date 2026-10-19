package org.unitedsoftware.alexandrite.mcp.oauth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.mcp.client.string
import java.net.URI
import java.time.Instant
import java.util.Base64

/** The tokens of one server from one authorization server, with what refreshing them takes. */
@Serializable
internal class StoredTokens(
    val server: String,
    val issuer: String,
    val tokenEndpoint: String,
    val clientId: String,
    val authMethod: String,
    val resource: String,
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch milliseconds, null when the server did not say. */
    val expiresAt: Long? = null,
    val scopes: List<String> = emptyList(),
) {
    fun refreshed(grant: TokenGrant, now: Instant): StoredTokens = StoredTokens(
        server,
        issuer,
        tokenEndpoint,
        clientId,
        authMethod,
        resource,
        grant.accessToken,
        grant.refreshToken ?: refreshToken,
        grant.expiresAt(now),
        grant.scopes ?: scopes,
    )
}

/** A client that an authorization server registered dynamically for [redirectUris]. */
@Serializable
internal class StoredClient(
    val issuer: String,
    val clientId: String,
    val clientSecret: String? = null,
    val authMethod: String = NONE,
    val redirectUris: List<String>,
)

/** How the host authenticates as the client [id] at a token endpoint. */
internal class ClientAuth(val id: String, val secret: String?, val method: String) {
    /** [form] with what [method] puts in the body, and the headers it adds. */
    fun apply(form: Map<String, String>): Pair<Map<String, String>, Map<String, String>> = when {
        secret != null && method == CLIENT_SECRET_BASIC -> {
            val pair = "${encode(id)}:${encode(secret)}"
            val basic = Base64.getEncoder().encodeToString(pair.toByteArray(Charsets.UTF_8))
            form to mapOf("Authorization" to "Basic $basic")
        }

        secret != null && method == CLIENT_SECRET_POST -> form + mapOf("client_id" to id, "client_secret" to secret) to
            emptyMap()

        else -> form + ("client_id" to id) to emptyMap()
    }

    val secrets: List<String> get() = listOfNotNull(secret)
}

/** What a token endpoint granted. */
internal class TokenGrant(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long?,
    /** Null when the grant names no scopes. */
    val scopes: List<String>?,
) {
    fun expiresAt(now: Instant): Long? = expiresIn?.let { now.toEpochMilli() + it * 1000 }
}

internal const val NONE: String = "none"
internal const val CLIENT_SECRET_BASIC: String = "client_secret_basic"
internal const val CLIENT_SECRET_POST: String = "client_secret_post"

/** The token and registration endpoints of authorization servers. */
internal class TokenRequests(private val http: OAuthHttp) {
    suspend fun exchange(
        endpoint: URI,
        client: ClientAuth,
        code: String,
        verifier: String,
        redirectUri: String,
        resource: String,
    ): TokenGrant = grant(
        endpoint,
        client,
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to verifier,
            "resource" to resource,
        ),
        listOf(code, verifier),
        "code exchange",
    )

    suspend fun refresh(endpoint: URI, client: ClientAuth, refreshToken: String, resource: String): TokenGrant = grant(
        endpoint,
        client,
        mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken, "resource" to resource),
        listOf(refreshToken),
        "token refresh",
    )

    /** Registers a client named [name] for [redirectUri] at [endpoint] (RFC 7591). */
    suspend fun register(
        endpoint: URI,
        issuer: String,
        name: String,
        redirectUri: String,
        native: Boolean,
    ): StoredClient {
        val body = buildJsonObject {
            put("client_name", name)
            put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirectUri))))
            put("grant_types", JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))))
            put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
            put("token_endpoint_auth_method", NONE)
            put("application_type", if (native) "native" else "web")
        }
        val answer = http.postJson(endpoint, body, "client registration")
        val id = answer.string("client_id")?.takeIf { it.isNotEmpty() }
            ?: throw OAuthException("Authorization server $issuer registered no client id.")
        val secret = answer.string("client_secret")?.takeIf { it.isNotEmpty() }
        val method = answer.string("token_endpoint_auth_method")
            ?: if (secret == null) NONE else CLIENT_SECRET_BASIC
        return StoredClient(issuer, id, secret, method, listOf(redirectUri))
    }

    private suspend fun grant(
        endpoint: URI,
        client: ClientAuth,
        form: Map<String, String>,
        secrets: List<String>,
        what: String,
    ): TokenGrant {
        val (body, headers) = client.apply(form)
        val answer = http.postForm(endpoint, body, headers, secrets + client.secrets, what)
        val access = answer.string("access_token")?.takeIf { it.isNotEmpty() }
            ?: throw OAuthException("The authorization server answered the $what with no access token.")
        val type = answer.string("token_type")
        if (type != null && !type.equals("bearer", ignoreCase = true)) {
            throw OAuthException("The authorization server answered the $what with a token of type '$type'.")
        }
        return TokenGrant(
            access,
            answer.string("refresh_token")?.takeIf { it.isNotEmpty() },
            (answer["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }
                ?.takeIf { it > 0 },
            answer.string("scope")?.split(' ')?.filter(String::isNotEmpty),
        )
    }
}
