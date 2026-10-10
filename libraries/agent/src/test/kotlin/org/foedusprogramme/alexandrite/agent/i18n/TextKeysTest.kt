package org.foedusprogramme.alexandrite.agent.i18n

import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
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
    fun `the agent ships its texts in English`() {
        assertEquals(listOf(LanguageTag("en")), assertTextsComplete("alexandrite-agent"))
    }

    @Test
    fun `every key the agent uses has an English text`() {
        val texts = testTexts("alexandrite-agent")

        assertEquals(15, keys.size)
        assertEquals(emptyList(), keys.filter { texts.text(it, null) == it })
        assertEquals(emptyList(), ModelErrorKind.entries.map(TextKeys::modelFailure).filter { it !in keys })
    }
}
