package org.unitedsoftware.alexandrite.internal.collection

import kotlin.test.Test
import kotlin.test.assertEquals

class BoundedMapTest {
    @Test
    fun `a full map drops the least recently used entry`() {
        val map = BoundedMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        map["a"]
        map["c"] = 3

        assertEquals(listOf("a", "c"), map.keys.toList())
    }

    @Test
    fun `without access order a full map drops the oldest entry`() {
        val map = BoundedMap<String, Int>(2, accessOrder = false)
        map["a"] = 1
        map["b"] = 2
        map["a"]
        map["c"] = 3

        assertEquals(listOf("b", "c"), map.keys.toList())
    }
}
