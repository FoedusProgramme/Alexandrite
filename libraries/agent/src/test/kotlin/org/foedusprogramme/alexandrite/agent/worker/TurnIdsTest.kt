package org.foedusprogramme.alexandrite.agent.worker

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnIdsTest {
    @Test
    fun `a turn id is t- and a ULID whose first characters encode the time`() {
        val ids = List(100) { newTurnId(Instant.parse("2026-01-01T00:00:00Z")).value }

        assertTrue(ids.all { Regex("t-[0-9A-HJKMNP-TV-Z]{26}").matches(it) }, ids.first())
        assertEquals(setOf("t-01KDVDNA00"), ids.map { it.take(12) }.toSet())
        assertEquals(100, ids.toSet().size)
        assertTrue(newTurnId(Instant.parse("2026-01-01T00:00:01Z")).value > ids.max())
    }
}
