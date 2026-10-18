package org.foedusprogramme.alexandrite.automation.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WhenTest {
    // Once.

    @Test
    fun `once is due at its local time and never again`() {
        val once = When.Once(local("2026-10-18T12:00"))
        assertEquals(instant("2026-10-18T10:00:00Z"), once.next(ORIGIN.minusSeconds(1), BERLIN))
        assertNull(once.next(instant("2026-10-18T10:00:00Z"), BERLIN))
        assertNull(once.next(ORIGIN.plusSeconds(3600), BERLIN))
    }

    @Test
    fun `once in the spring gap moves forward by the gap`() {
        assertEquals(
            instant("2026-03-29T01:30:00Z"),
            When.Once(local("2026-03-29T02:30")).next(instant("2026-03-01T00:00:00Z"), BERLIN),
        )
        assertEquals(
            instant("2026-03-08T07:30:00Z"),
            When.Once(local("2026-03-08T02:30")).next(instant("2026-03-01T00:00:00Z"), NEW_YORK),
        )
    }

    @Test
    fun `once in the autumn overlap takes the earlier instant`() {
        assertEquals(
            instant("2026-10-25T00:30:00Z"),
            When.Once(local("2026-10-25T02:30")).next(instant("2026-10-01T00:00:00Z"), BERLIN),
        )
        assertEquals(
            instant("2026-11-01T05:30:00Z"),
            When.Once(local("2026-11-01T01:30")).next(instant("2026-10-01T00:00:00Z"), NEW_YORK),
        )
    }

    // Every.

    @Test
    fun `every starts at from and steps strictly after`() {
        val every = When.Every(15.minutes, ORIGIN)
        assertEquals(ORIGIN, every.next(ORIGIN.minusSeconds(3600), UTC))
        assertEquals(ORIGIN.plusSeconds(900), every.next(ORIGIN, UTC))
        assertEquals(ORIGIN.plusSeconds(900), every.next(ORIGIN.plusSeconds(1), UTC))
        assertEquals(ORIGIN.plusSeconds(1800), every.next(ORIGIN.plusSeconds(900), UTC))
    }

    @Test
    fun `every keeps an absolute interval across daylight saving`() {
        val daily = When.Every(24.hours, instant("2026-03-28T12:00:00Z"))
        assertEquals(instant("2026-03-29T12:00:00Z"), daily.next(instant("2026-03-28T12:00:00Z"), BERLIN))
        assertEquals(instant("2026-10-25T12:00:00Z"), daily.next(instant("2026-10-25T11:00:00Z"), BERLIN))
    }

    @Test
    fun `every refuses an interval that is not positive`() {
        for (interval in listOf(Duration.ZERO, (-1).seconds)) {
            assertFailsWith<IllegalArgumentException> { When.Every(interval, ORIGIN) }
        }
    }

    // Cron.

    @Test
    fun `cron is due by its expression in the zone`() {
        val daily = When.Cron(cron("30 2 * * *"))
        assertEquals(instant("2026-03-29T01:00:00Z"), daily.next(instant("2026-03-28T12:00:00Z"), BERLIN))
        assertEquals(instant("2026-10-25T00:30:00Z"), daily.next(instant("2026-10-24T12:00:00Z"), BERLIN))
        assertEquals(When.Cron(cron("@daily")), When.Cron(cron("@midnight")))
    }
}
