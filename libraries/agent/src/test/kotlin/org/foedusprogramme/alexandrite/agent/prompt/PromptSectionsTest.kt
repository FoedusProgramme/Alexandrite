package org.foedusprogramme.alexandrite.agent.prompt

import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.model.PromptSection
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptSectionsTest {
    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name")) { "No golden file $name." }.readText().removeSuffix("\n")

    @Test
    fun `the base section keeps its bytes`() {
        assertEquals(
            PromptSection("alexandrite.base", golden("alexandrite.base.txt"), stable = true),
            baseSection("Coder"),
        )
    }

    @Test
    fun `the language section names the tag`() {
        assertEquals(
            PromptSection(
                "chat.language",
                "Answer in zh-CN unless the user writes in another language.",
                stable = true,
            ),
            languageSection(LanguageTag("zh-CN")),
        )
    }

    @Test
    fun `instruction sections are numbered from 1`() {
        assertEquals(listOf("agent.instructions.1", "agent.instructions.12"), listOf(1, 12).map(::instructionsSection))
    }
}
