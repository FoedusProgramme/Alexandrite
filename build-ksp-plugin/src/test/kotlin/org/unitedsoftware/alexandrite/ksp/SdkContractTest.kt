package org.unitedsoftware.alexandrite.ksp

import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import kotlin.test.Test
import kotlin.test.assertEquals

class SdkContractTest {
    @Test
    fun `the id rules are the SDK's`() {
        assertEquals(PluginIds.PATTERN.pattern, PLUGIN_ID.pattern)
        assertEquals(PluginIds.PATTERN.pattern, CHANNEL_TYPE.pattern)
        assertEquals(PluginIds.RESERVED_PREFIX, RESERVED_PREFIX)
        assertEquals(PluginIds.THIRD_PARTY_ROOT, THIRD_PARTY_ROOT)
        assertEquals(PluginIds.ENABLED_KEY, ENABLED)
        assertEquals(PluginIds.INSTANCES_KEY, INSTANCES)
    }
}
