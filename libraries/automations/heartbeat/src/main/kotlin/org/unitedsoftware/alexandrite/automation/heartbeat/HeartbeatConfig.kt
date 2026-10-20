package org.unitedsoftware.alexandrite.automation.heartbeat

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.automation.common.ActiveHours
import org.unitedsoftware.alexandrite.automation.common.parseDuration
import org.unitedsoftware.alexandrite.internal.time.zoneOrNull
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@ConfigSection
@Serializable
internal class HeartbeatConfig(
    /** The heartbeats by the ids of their agents. */
    val agents: Map<String, AgentHeartbeatConfig> = emptyMap(),
) {
    init {
        heartbeats(ZoneOffset.UTC)
    }

    /** The heartbeats in config order, each in [hostZone] when it names no zone. */
    fun heartbeats(hostZone: ZoneId): List<Heartbeat> =
        agents.map { (id, heartbeat) -> heartbeat.read("agents.$id", id, hostZone) }
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
    /** The heartbeat of agent [id] at [path], in [hostZone] when it names no zone. */
    fun read(path: String, id: String, hostZone: ZoneId): Heartbeat {
        val agent = try {
            AgentId(id)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "$path is no agent id: an id is lowercase words of letters and digits, each starting with a letter, " +
                    "joined by single hyphens, such as \"coder\"",
                e,
            )
        }
        val interval = try {
            parseDuration(every)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$path.every: ${e.message}", e)
        }
        require(interval >= MIN_INTERVAL) { "$path.every must be $MIN_INTERVAL at least, was $interval" }
        require(prompt == null || prompt.isNotBlank()) { "$path.prompt may not be blank" }
        val hours = activeHours?.let {
            try {
                ActiveHours.parse(it)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "$path.activeHours must be written HH:mm-HH:mm, such as \"08:00-23:00\" or \"22:00-06:00\"",
                    e,
                )
            }
        }
        val zoneId = zone?.let {
            requireNotNull(zoneOrNull(it)) {
                "$path.zone is no time zone: write a region id such as \"Asia/Shanghai\" or an offset such as \"+08:00\""
            }
        }
        return Heartbeat(agent, interval, prompt ?: DEFAULT_PROMPT, hours, zoneId ?: hostZone)
    }
}

internal val MIN_INTERVAL: Duration = 5.minutes
