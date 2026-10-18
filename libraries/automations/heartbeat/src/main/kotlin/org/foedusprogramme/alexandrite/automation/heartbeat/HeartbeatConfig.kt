package org.foedusprogramme.alexandrite.automation.heartbeat

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.automation.common.ActiveHours
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import java.time.DateTimeException
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@ConfigSection
@Serializable
internal class HeartbeatConfig(
    /** The heartbeats by the ids of their agents. */
    val agents: Map<String, AgentHeartbeatConfig> = emptyMap(),
) {
    init {
        for ((id, heartbeat) in agents) heartbeat.check("agents.$id", id)
    }
}

@Serializable
internal class AgentHeartbeatConfig(
    /** How often the agent beats, such as `30m`, `2h` or `1h30m`. */
    val every: String,
    /** What each beat asks the agent, the default prompt when null. */
    val prompt: String? = null,
    /** The part of the day beats run in, such as `08:00-23:00`, the whole day when null. */
    val activeHours: String? = null,
    /** The zone of [activeHours], a region id or an offset, the host's when null. */
    val zone: String? = null,
) {
    fun check(path: String, id: String) {
        require(PluginIds.PATTERN.matches(id)) {
            "$path is no agent id: an id is lowercase words of letters and digits, each starting with a letter, " +
                "joined by single hyphens, such as \"coder\""
        }
        val interval = interval(every)
        require(interval != null) {
            "$path.every is no interval: write minutes and hours, such as \"30m\" or \"1h30m\""
        }
        require(interval >= MIN_INTERVAL) { "$path.every must be $MIN_INTERVAL at least, was $interval" }
        require(prompt == null || prompt.isNotBlank()) { "$path.prompt may not be blank" }
        require(activeHours == null || activeHoursOrNull(activeHours) != null) {
            "$path.activeHours must be written HH:mm-HH:mm, such as \"08:00-23:00\" or \"22:00-06:00\""
        }
        require(zone == null || zoneOrNull(zone) != null) {
            "$path.zone is no time zone: write a region id such as \"Asia/Shanghai\" or an offset such as \"+08:00\""
        }
    }
}

/** The interval [text] names, such as `1h30m`, null when it names none. */
internal fun interval(text: String): Duration? {
    val match = INTERVAL.matchEntire(text.trim()) ?: return null
    val (hours, minutes) = match.destructured
    if (hours.isEmpty() && minutes.isEmpty()) return null
    return (hours.toLongOrNull() ?: 0).hours + (minutes.toLongOrNull() ?: 0).minutes
}

/** The active hours [text] names, null when it names none. */
internal fun activeHoursOrNull(text: String): ActiveHours? = try {
    ActiveHours.parse(text)
} catch (e: IllegalArgumentException) {
    null
}

/** The zone [text] names, a region id or an offset, null when it names none. */
internal fun zoneOrNull(text: String): ZoneId? = try {
    ZoneId.of(text)
} catch (e: DateTimeException) {
    null
}

internal val MIN_INTERVAL: Duration = 5.minutes

private val INTERVAL = Regex("(?:(\\d{1,4})h)?(?:(\\d{1,6})m)?")
