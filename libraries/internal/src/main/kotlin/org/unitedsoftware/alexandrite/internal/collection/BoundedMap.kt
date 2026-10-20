package org.unitedsoftware.alexandrite.internal.collection

/** A map of [max] entries at most, which drops the least recently used first, or the oldest unless [accessOrder]. */
public class BoundedMap<K, V>(private val max: Int, accessOrder: Boolean = true) :
    LinkedHashMap<K, V>(16, 0.75f, accessOrder) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
}
