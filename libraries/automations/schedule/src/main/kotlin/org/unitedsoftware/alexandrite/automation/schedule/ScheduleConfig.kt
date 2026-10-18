package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection

@ConfigSection
@Serializable
internal class ScheduleConfig(
    /** How late an occurrence may still run after the host was down. */
    val missedWindowHours: Int = 24,
    val maxPerChat: Int = 50,
    val maxPerAgent: Int = 500,
    /** The shortest interval a recurring task may repeat at. */
    val minIntervalMinutes: Int = 5,
) {
    init {
        require(missedWindowHours >= 0) { "missedWindowHours may not be negative" }
        require(maxPerChat > 0) { "maxPerChat must be positive" }
        require(maxPerAgent >= maxPerChat) { "maxPerAgent must be at least maxPerChat" }
        require(minIntervalMinutes > 0) { "minIntervalMinutes must be positive" }
    }
}
