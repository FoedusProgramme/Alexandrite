package org.unitedsoftware.alexandrite.internal.time

import java.time.DateTimeException
import java.time.ZoneId
import java.util.Locale

/** The zone [text] names, a region id in any case or an offset such as `+08:00`, null when it names none. */
public fun zoneOrNull(text: String): ZoneId? {
    val region = ZoneId.getAvailableZoneIds().firstOrNull { it.equals(text, ignoreCase = true) }
    return try {
        ZoneId.of(region ?: text.uppercase(Locale.ROOT))
    } catch (e: DateTimeException) {
        null
    }
}
