package org.foedusprogramme.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.turn.PromptSections
import org.foedusprogramme.alexandrite.testkit.testTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(InternalAlexandriteApi::class)
class QqBotPromptHookTest {
    private val hook = QqBotPromptHook()

    @Test
    fun `the prompt of a qqbot turn says what the client renders`() = runBlocking {
        val turn = testTurn(chat = chat("qqbot"))
        val decision = hook.intercept(PromptSections(turn, emptyList()))

        val payload = assertIs<HookDecision.Replace<PromptSections>>(decision).payload
        val section = payload.sections.single { it.id == "qqbot.platform" }
        assertTrue("GFM tables" in section.text)
        assertTrue("[TOC]" in section.text)
    }

    @Test
    fun `the prompt of another channel is left alone`() = runBlocking {
        val turn = testTurn(chat = chat("onebot"))

        assertEquals(HookDecision.Continue, hook.intercept(PromptSections(turn, emptyList())))
    }

    private fun chat(type: String) = ChatAddress(ChannelInstanceId(ChannelType(type), "main"), "private:u1")
}
