package org.foedusprogramme.alexandrite.channel.telegram.i18n

import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.testkit.assertTextsComplete
import org.foedusprogramme.alexandrite.testkit.testTexts
import java.lang.reflect.Modifier
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class TextKeysTest {
    private val keys = TextKeys::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }

    @Test
    fun `the channel ships its texts in English`() {
        assertEquals(listOf(LanguageTag("en")), assertTextsComplete(PLUGIN))
    }

    @Test
    fun `every key the channel uses has an English text, and every English text is used`() {
        val english = Properties()
        javaClass.getResourceAsStream("/alexandrite/i18n/$PLUGIN/en.properties")!!.use { english.load(it) }
        val texts = testTexts(PLUGIN)

        assertEquals(english.stringPropertyNames(), keys.toSet())
        assertEquals(emptyList(), keys.filter { texts.text(it, null) == it })
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-telegram"
    }
}
