package org.unitedsoftware.alexandrite.internal.time

import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZonesTest {
    @Test
    fun `a region in any case and an offset name a zone`() {
        assertEquals(ZoneId.of("Asia/Shanghai"), zoneOrNull("asia/shanghai"))
        assertEquals(ZoneId.of("UTC"), zoneOrNull("utc"))
        assertEquals(ZoneOffset.ofHours(8), zoneOrNull("+08:00"))
        assertNull(zoneOrNull("Mars/Olympus"))
    }
}
