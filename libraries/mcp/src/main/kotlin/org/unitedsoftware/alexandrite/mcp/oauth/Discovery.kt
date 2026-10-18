package org.unitedsoftware.alexandrite.mcp.oauth

import kotlinx.serialization.json.JsonObject
import java.net.URI

/** What a protected MCP server says of itself (RFC 9728). */
internal class ResourceMetadata(
    val resource: String?,
    val authorizationServers: List<String>,
    val scopesSupported: List<String>?,
)

/** What an authorization server says of itself (RFC 8414, OpenID Connect Discovery). */
internal class ServerMetadata(
    val issuer: String,
    val authorizationEndpoint: URI,
    val tokenEndpoint: URI,
    val registrationEndpoint: URI?,
    val scopesSupported: List<String>?,
    /** Whether its authorization responses carry `iss` (RFC 9207). */
    val issParameter: Boolean,
    val clientIdMetadataDocuments: Boolean,
    val tokenAuthMethods: List<String>?,
)

/** Finds a server's authorization server and what it offers. */
internal class Discovery(private val http: OAuthHttp) {
    /**
     * The metadata of the protected resource at [server]: at the address of [challenge] when it names one, else at the
     * well-known paths, else that of a server whose own origin is its authorization server.
     */
    suspend fun resource(server: URI, challenge: Challenge?): ResourceMetadata {
        val named = challenge?.resourceMetadata
        if (named != null) {
            val uri = secureUri(named, "resource metadata address")
            val json = http.find(uri, RESOURCE_METADATA)
                ?: throw OAuthException("The server names resource metadata at $named that cannot be fetched.")
            return resourceOf(json)
        }
        for (uri in wellKnown(server, RESOURCE_SUFFIX, insertOnly = true)) {
            http.find(uri, RESOURCE_METADATA)?.let { return resourceOf(it) }
        }
        return ResourceMetadata(null, listOf(origin(server)), null)
    }

    /** The checked metadata of the authorization server [issuer], from the first well-known path that has it. */
    suspend fun authorizationServer(issuer: String): ServerMetadata {
        val uri = secureUri(issuer, "authorization server")
        if (uri.rawQuery != null) throw OAuthException("The authorization server '$issuer' has a query.")
        for (candidate in wellKnown(uri, AUTHORIZATION_SUFFIX, insertOnly = false)) {
            val json = http.find(candidate, SERVER_METADATA) ?: continue
            if (json.string("issuer") != issuer) {
                throw OAuthException(
                    "The metadata of authorization server $issuer names another issuer, " +
                        "'${json.string("issuer")}', so it is not used.",
                )
            }
            return serverOf(issuer, json)
        }
        throw OAuthException("Authorization server $issuer publishes no metadata at its well-known addresses.")
    }

    private fun resourceOf(json: JsonObject): ResourceMetadata {
        val servers = json.strings("authorization_servers").orEmpty()
        if (servers.isEmpty()) throw OAuthException("The server's resource metadata names no authorization server.")
        return ResourceMetadata(json.string("resource"), servers, json.strings("scopes_supported"))
    }

    private fun serverOf(issuer: String, json: JsonObject): ServerMetadata {
        fun endpoint(name: String): URI? = json.string(name)?.let { secureUri(it, name) }
        val methods = json.strings("code_challenge_methods_supported")
        if (methods == null || S256 !in methods) {
            throw OAuthException(
                "Authorization server $issuer does not offer PKCE with S256, so the login cannot run safely.",
            )
        }
        return ServerMetadata(
            issuer,
            endpoint("authorization_endpoint")
                ?: throw OAuthException("Authorization server $issuer names no authorization endpoint."),
            endpoint("token_endpoint") ?: throw OAuthException("Authorization server $issuer names no token endpoint."),
            endpoint("registration_endpoint"),
            json.strings("scopes_supported"),
            json.flag("authorization_response_iss_parameter_supported"),
            json.flag("client_id_metadata_document_supported"),
            json.strings("token_endpoint_auth_methods_supported"),
        )
    }

    companion object {
        const val S256: String = "S256"
        private const val RESOURCE_METADATA = "resource metadata"
        private const val SERVER_METADATA = "authorization server metadata"
        private const val RESOURCE_SUFFIX = "oauth-protected-resource"
        private const val AUTHORIZATION_SUFFIX = "oauth-authorization-server"
        private const val OPENID_SUFFIX = "openid-configuration"

        /** The scheme, host and port of [uri]. */
        fun origin(uri: URI): String =
            URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null)
                .toString()

        /**
         * The well-known addresses of [uri] for [suffix] in the order MCP tries them: the path inserted, then the root,
         * or for an authorization server also OpenID Connect's, inserted and appended.
         */
        fun wellKnown(uri: URI, suffix: String, insertOnly: Boolean): List<URI> {
            val origin = origin(uri)
            val path = uri.rawPath.orEmpty().trimEnd('/')
            if (insertOnly) {
                return listOfNotNull(
                    "$origin/.well-known/$suffix$path".takeIf { path.isNotEmpty() },
                    "$origin/.well-known/$suffix",
                ).map(::URI)
            }
            val candidates = if (path.isEmpty()) {
                listOf("$origin/.well-known/$suffix", "$origin/.well-known/$OPENID_SUFFIX")
            } else {
                listOf(
                    "$origin/.well-known/$suffix$path",
                    "$origin/.well-known/$OPENID_SUFFIX$path",
                    "$origin$path/.well-known/$OPENID_SUFFIX",
                )
            }
            return candidates.map(::URI)
        }
    }
}
