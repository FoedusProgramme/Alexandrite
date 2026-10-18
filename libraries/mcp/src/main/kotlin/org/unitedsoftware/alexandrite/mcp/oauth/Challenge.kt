package org.unitedsoftware.alexandrite.mcp.oauth

import org.unitedsoftware.alexandrite.internal.hash.sha256
import java.security.SecureRandom
import java.util.Base64

/** The `Bearer` challenge of a `WWW-Authenticate` header. */
internal class Challenge(val resourceMetadata: String?, val scopes: List<String>, val error: String?) {
    val insufficientScope: Boolean get() = error == INSUFFICIENT_SCOPE

    companion object {
        const val INSUFFICIENT_SCOPE: String = "insufficient_scope"

        /** The Bearer challenge of [header], null when it has none. */
        fun parse(header: String?): Challenge? {
            if (header == null) return null
            val params = bearerParams(header) ?: return null
            val scopes = params["scope"]?.split(' ', '\t')?.filter(String::isNotEmpty).orEmpty()
            return Challenge(params["resource_metadata"], scopes, params["error"])
        }

        /** The parameters of the Bearer challenge among those of [header], by lowercase name. */
        private fun bearerParams(header: String): Map<String, String>? {
            var bearer: MutableMap<String, String>? = null
            var scheme: String? = null
            var index = 0
            fun skip(chars: String) {
                while (index < header.length && header[index] in chars) index++
            }
            fun token(): String {
                val start = index
                while (index < header.length && header[index] !in " \t,=") index++
                return header.substring(start, index)
            }
            fun quoted(): String {
                val (value, next) = quotedAt(header, index)
                index = next
                return value
            }
            while (index < header.length) {
                skip(" \t,")
                val word = token()
                if (word.isEmpty()) {
                    index++
                    continue
                }
                skip(" \t")
                if (index < header.length && header[index] == '=') {
                    index++
                    skip(" \t")
                    val value = if (index < header.length && header[index] == '"') quoted() else token()
                    if (scheme == BEARER) bearer?.putIfAbsent(word.lowercase(), value)
                } else {
                    scheme = word.lowercase()
                    if (scheme == BEARER && bearer == null) bearer = LinkedHashMap()
                }
            }
            return bearer
        }

        private const val BEARER = "bearer"
    }
}

/** The quoted string that starts at [start], and the index past its closing quote. */
private fun quotedAt(header: String, start: Int): Pair<String, Int> {
    val value = StringBuilder()
    var index = start + 1
    while (index < header.length && header[index] != '"') {
        if (header[index] == '\\' && index + 1 < header.length) index++
        value.append(header[index])
        index++
    }
    return value.toString() to (index + 1).coerceAtMost(header.length)
}

/** The PKCE pair of one login, the challenge of [verifier] made with S256. */
internal class Pkce(val verifier: String) {
    val challenge: String = BASE64.encodeToString(sha256(verifier.toByteArray(Charsets.US_ASCII)))

    companion object {
        fun create(): Pkce = Pkce(randomToken())
    }
}

/** A random URL-safe string of [bytes] random bytes. */
internal fun randomToken(bytes: Int = 32): String = BASE64.encodeToString(ByteArray(bytes).also(RANDOM::nextBytes))

private val RANDOM = SecureRandom()
private val BASE64 = Base64.getUrlEncoder().withoutPadding()
