package org.foedusprogramme.alexandrite.tools.web

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

@ConfigSection("web")
@Serializable
internal class WebConfig(
    val model: String? = null,
    val allowedPrivateHosts: Set<String> = emptySet(),
    val timeoutSeconds: Int = 30,
    val maxBytes: Int = 1_000_000,
    val maxTextChars: Int = 100_000,
    val searchBackend: String? = null,
    val braveApiKey: Secret? = null,
    val tavilyApiKey: Secret? = null,
) {
    val normalizedPrivateHosts: Set<String> get() = allowedPrivateHosts.map(::normalizeAllowedHost).toSet()

    init {
        allowedPrivateHosts.forEach(::normalizeAllowedHost)
        require(timeoutSeconds in 1..300)
        require(maxBytes in 1..10_000_000)
        require(maxTextChars in 1..1_000_000)
        model?.let(ModelRef::parse)
        require(braveApiKey == null || braveApiKey.reveal().isNotBlank())
        require(tavilyApiKey == null || tavilyApiKey.reveal().isNotBlank())
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
