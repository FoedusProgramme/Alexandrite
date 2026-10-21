package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.internal.time.zoneOrNull
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
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
    val updates: UpdatesConfig = UpdatesConfig(),
) {
    init {
        require(zone == null || zoneOrNull(zone) != null) {
            "zone '$zone' is no time zone: write a region id such as \"Asia/Shanghai\" or an offset such as \"+08:00\""
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

    val zoneId: ZoneId get() = zone?.let(::zoneOrNull) ?: ZoneId.systemDefault()

    val languageTag: LanguageTag get() = LanguageTag.of(language)
}

@Serializable
internal class UpdatesConfig(
    /** Whether the app asks once a day for the latest release. */
    val check: Boolean = true,
    /** Whether the app installs a newer release by itself once nobody wrote for [idleMinutes]. */
    val install: Boolean = false,
    val idleMinutes: Long = 120,
) {
    init {
        require(idleMinutes > 0) { "updates.idleMinutes must be positive" }
        require(check || !install) { "updates.install needs updates.check, which is off" }
    }
}
