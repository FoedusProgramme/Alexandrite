package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TextCatalogTest {
    private val english = LanguageTag("en")
    private val chinese = LanguageTag("zh-CN")

    private val catalog = TextCatalog.builder()
        .texts("notes", chinese, mapOf("saved" to "已保存。", "deleted" to "已删除。"))
        .text("notes", english, "saved", "Kept.")
        .text("agent", chinese, "failed", "失败了。")
        .build()

    @Test
    fun `a catalog holds texts by plugin, language and key`() {
        assertEquals(setOf("agent", "notes"), catalog.plugins)
        assertEquals(setOf(english, chinese), catalog.languages("notes"))
        assertEquals(mapOf("deleted" to "已删除。", "saved" to "已保存。"), catalog.texts("notes", chinese))
        assertEquals("Kept.", catalog.text("notes", english, "saved"))
        assertNull(catalog.text("notes", english, "deleted"))
        assertEquals(emptyMap(), catalog.texts("tools", english))
        assertEquals(emptySet(), catalog.languages("tools"))
    }

    @Test
    fun `a builder replaces the texts it is given again and keeps the others`() {
        val changed = catalog.toBuilder()
            .texts("notes", chinese, mapOf("saved" to "存好了。"))
            .texts("tools", english, emptyMap())
            .build()

        assertEquals(mapOf("deleted" to "已删除。", "saved" to "存好了。"), changed.texts("notes", chinese))
        assertEquals(setOf("agent", "notes"), changed.plugins)
        assertEquals("已保存。", catalog.text("notes", chinese, "saved"))
        assertEquals(TextCatalog.EMPTY, TextCatalog.builder().texts("notes", english, emptyMap()).build())
    }

    @Test
    fun `a catalog is equal by its texts and prints its plugins and languages`() {
        val same = TextCatalog.builder()
            .text("agent", chinese, "failed", "失败了。")
            .text("notes", english, "saved", "Kept.")
            .texts("notes", chinese, mapOf("deleted" to "已删除。", "saved" to "已保存。"))
            .build()

        assertEquals(catalog, same)
        assertEquals(catalog.hashCode(), same.hashCode())
        assertNotEquals(catalog, catalog.toBuilder().text("agent", chinese, "failed", "错了。").build())
        assertEquals("TextCatalog(agent=[zh-CN], notes=[en, zh-CN])", "$catalog")
    }

    @Test
    fun `a malformed plugin id is refused`() {
        assertFailsWith<IllegalArgumentException> { TextCatalog.builder().text("Notes", english, "saved", "Kept.") }
    }
}
