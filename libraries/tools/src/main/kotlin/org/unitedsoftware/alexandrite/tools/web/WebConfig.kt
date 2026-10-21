package org.unitedsoftware.alexandrite.tools.web

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.web.WebSearchBackend
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

@ConfigSection("web")
@Serializable
internal class WebConfig(
    /** The side model that answers `web.fetch` from a page, `endpoint/model`; the agent's judge model when null. */
    val model: String? = null,
    /** Private hosts that `web.fetch` may reach all the same, by exact name or IP literal. */
    val allowedPrivateHosts: Set<String> = emptySet(),
    /** How long one fetch or search may take, at most 300 s. */
    val timeoutSeconds: Int = 30,
    /** The largest response body a fetch reads. */
    val maxBytes: Int = 1_000_000,
    /** The characters of a page's text that the side model reads. */
    val maxTextChars: Int = 100_000,
    /** The id of the search backend that `web.search` uses where the model's endpoint has no search of its own. */
    val searchBackend: String? = null,
    /** The key of the built-in Brave Search backend, best given as an environment reference. */
    val braveApiKey: Secret? = null,
    /** The key of the built-in Tavily backend, best given as an environment reference. */
    val tavilyApiKey: Secret? = null,
) {
    val normalizedPrivateHosts: Set<String> get() = allowedPrivateHosts.map(::normalizeAllowedHost).toSet()

    init {
        allowedPrivateHosts.forEach(::normalizeAllowedHost)
        require(timeoutSeconds in 1..300) { "timeoutSeconds must be from 1 to 300" }
        require(maxBytes in 1..10_000_000) { "maxBytes must be from 1 to 10000000" }
        require(maxTextChars in 1..1_000_000) { "maxTextChars must be from 1 to 1000000" }
        model?.let(ModelRef::parse)
        require(searchBackend == null || WebSearchBackend.ID.matches(searchBackend)) {
            "searchBackend '$searchBackend' is no backend id: lowercase letters, digits and '-', such as \"brave\""
        }
        require(braveApiKey == null || braveApiKey.reveal().isNotBlank()) { "braveApiKey may not be blank" }
        require(tavilyApiKey == null || tavilyApiKey.reveal().isNotBlank()) { "tavilyApiKey may not be blank" }
    }
}

internal fun normalizeAllowedHost(value: String): String {
    require(value.isNotBlank() && value.none { it.isWhitespace() || it in "*/\\?#@%" }) {
        "allowedPrivateHosts requires exact host names or IP literals."
    }
    val authority = if (':' in value && !value.startsWith('[')) "[$value]" else value
    val uri = try {
        URI("http://$authority/")
    } catch (e: URISyntaxException) {
        throw IllegalArgumentException("Invalid allowed private host.", e)
    }
    require(
        uri.userInfo == null && uri.port == -1 && uri.rawPath == "/" &&
            uri.rawQuery == null && uri.rawFragment == null && uri.host != null,
    ) {
        "allowedPrivateHosts requires hosts, not URLs or ports."
    }
    val host = uri.host.removeSurrounding("[", "]").lowercase(Locale.ROOT).removeSuffix(".")
    require(host.isNotEmpty() && !host.endsWith('.')) { "Invalid allowed private host." }
    return host
}
