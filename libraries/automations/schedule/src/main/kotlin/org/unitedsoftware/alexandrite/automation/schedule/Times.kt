package org.unitedsoftware.alexandrite.automation.schedule

import org.unitedsoftware.alexandrite.automation.common.CronExpression
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/** [instant] in [zone], such as `2026-10-19 09:00 +08:00`. */
internal fun shown(instant: Instant, zone: ZoneId): String = MINUTES.format(instant.atZone(zone))

/** The day of [instant] in [zone], such as `2026-10-18`. */
internal fun day(instant: Instant, zone: ZoneId): String = DAY.format(instant.atZone(zone))

/** [duration] in days, hours and minutes, such as `1h30m`. */
internal fun shown(duration: Duration): String {
    val minutes = duration.inWholeMinutes
    if (minutes <= 0) return "0m"
    return buildString {
        val days = minutes / MINUTES_PER_DAY
        val hours = minutes % MINUTES_PER_DAY / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        if (days > 0) append(days).append('d')
        if (hours > 0) append(hours).append('h')
        if (rest > 0) append(rest).append('m')
    }
}

/** The first gap shorter than [minimum] between two firings of [cron] in [zone] in the year after [from], else null. */
internal fun gapUnder(minimum: Duration, cron: CronExpression, zone: ZoneId, from: Instant): Duration? {
    val end = from.plus(SCAN_DAYS, ChronoUnit.DAYS)
    var previous = cron.next(from, zone) ?: return null
    repeat(MAX_SCANNED) {
        val next = cron.next(previous, zone)?.takeIf { it <= end } ?: return null
        val gap = java.time.Duration.between(previous, next).toKotlinDuration()
        if (gap < minimum) return gap
        previous = next
    }
    return null
}

/** A time form that cannot be read, with the reason the model reads. */
internal class TimeFormException(message: String) : IllegalArgumentException(message)

private val MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx")
private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * 60
private const val SCAN_DAYS = 366L
private const val MAX_SCANNED = 200_000
