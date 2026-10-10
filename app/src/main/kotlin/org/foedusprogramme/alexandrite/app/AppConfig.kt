package org.foedusprogramme.alexandrite.app

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import java.time.DateTimeException
import java.time.ZoneId

@ConfigSection
@Serializable
internal class AppConfig(
    /** The runtime's zone, the system's when null. */
    val zone: String? = null,
    /** The language of texts for people where nothing else names one. */
    val language: String = "en",
    val shutdownGraceSeconds: Long = 15,
    val startTimeoutSeconds: Long = 30,
    /** Ids of the plugins to load by name besides the built-in ones. */
    val plugins: List<String> = emptyList(),
) {
    init {
        if (zone != null) {
            try {
                ZoneId.of(zone)
            } catch (e: DateTimeException) {
                throw IllegalArgumentException("zone '$zone' is no time zone: ${e.message}", e)
            }
        }
        try {
            LanguageTag.of(language)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("language '$language' is no BCP 47 language tag, such as zh-CN", e)
        }
        require(shutdownGraceSeconds >= 0) { "shutdownGraceSeconds may not be negative" }
        require(startTimeoutSeconds > 0) { "startTimeoutSeconds must be positive" }
        plugins.forEach { require(PluginIds.PATTERN.matches(it)) { "plugins holds '$it', which is no plugin id" } }
    }

    val zoneId: ZoneId get() = zone?.let(ZoneId::of) ?: ZoneId.systemDefault()

    val languageTag: LanguageTag get() = LanguageTag.of(language)
}
