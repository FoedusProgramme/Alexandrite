package org.unitedsoftware.alexandrite.internal.id

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomIdsTest {
    @Test
    fun `ids are 22 URL-safe characters and do not repeat`() {
        val ids = List(1000) { newId() }

        assertEquals(1000, ids.toSet().size)
        assertTrue(ids.all { Regex("[A-Za-z0-9_-]{22}").matches(it) })
    }
}
