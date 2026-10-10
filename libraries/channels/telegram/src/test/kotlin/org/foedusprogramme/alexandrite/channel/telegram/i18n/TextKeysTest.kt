package org.foedusprogramme.alexandrite.channel.telegram.i18n

import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.testkit.assertTextsComplete
import org.foedusprogramme.alexandrite.testkit.testTexts
import java.lang.reflect.Modifier
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
    fun `every key the channel uses has an English text`() {
        val texts = testTexts(PLUGIN)

        assertEquals(7, keys.size)
        assertEquals(emptyList(), keys.filter { texts.text(it, null) == it })
        assertEquals(
            "Only an admin of this bot may use /model.",
            texts.text(TextKeys.REFUSED_ADMIN, null, "command" to "model"),
        )
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-telegram"
    }
}
