package org.foedusprogramme.alexandrite.tools.web

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef

@ConfigSection("web")
@Serializable
internal class WebConfig(
    val model: String? = null,
    val timeoutSeconds: Int = 30,
    val maxBytes: Int = 1_000_000,
    val maxTextChars: Int = 100_000,
    val searchBackend: String? = null,
    val braveApiKey: Secret? = null,
    val tavilyApiKey: Secret? = null,
) {
    init {
        require(timeoutSeconds in 1..300)
        require(maxBytes in 1..10_000_000)
        require(maxTextChars in 1..1_000_000)
        model?.let(ModelRef::parse)
        require(braveApiKey == null || braveApiKey.reveal().isNotBlank())
        require(tavilyApiKey == null || tavilyApiKey.reveal().isNotBlank())
    }
}
