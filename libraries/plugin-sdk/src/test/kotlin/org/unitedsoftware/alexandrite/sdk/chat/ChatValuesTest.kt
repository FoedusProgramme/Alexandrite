package org.unitedsoftware.alexandrite.sdk.chat

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatValuesTest {
    private val work = ChannelInstanceId(ChannelType("telegram"), "work")
    private val chat = ChatAddress(work, "-100")
    private val operator = ChatUser(UserAddress(work, "1"), "Ada", "ada", isBot = false, isOperator = true)

    private fun turn(): TurnInfo.Builder = TurnInfo.builder(TurnId("t1"), chat, ConversationId("c1"), TurnKind.MESSAGE)

    // Turn identity.

    @Test
    fun `a turn defaults to the main agent, the chat as reply target, no principal and no lineage`() {
        val turn = turn().build()

        assertEquals(AgentId.MAIN, turn.agent)
        assertEquals(ReplyTarget.CHAT, turn.replyTarget)
        assertNull(turn.actor)
        assertNull(turn.lineage)
        assertFalse(turn.actorIsOperator)
        assertTrue(turn().actor(operator).build().actorIsOperator)
    }

    @Test
    fun `a turn's key is its agent at its chat unless it is kept at another chat`() {
        val anchor = AgentChatKey(AgentId("coder"), ChatAddress(work, "-200"))
        val linked = turn().agent(AgentId("coder")).key(anchor).build()

        assertEquals(AgentChatKey(AgentId.MAIN, chat), turn().build().key)
        assertEquals(AgentChatKey(AgentId("coder"), chat), turn().agent(AgentId("coder")).build().key)
        assertEquals(anchor, linked.key)
        assertEquals(chat, linked.chat)
        assertEquals(AgentChatKey(AgentId("helper"), chat), turn().build().rebuild { agent(AgentId("helper")) }.key)
        assertFailsWith<IllegalArgumentException> { linked.rebuild { agent(AgentId("helper")) } }
    }

    @Test
    fun `a turn rebuilt under another id keeps the rest, and a lineage has a positive depth`() {
        val turn = turn().actor(operator).language(LanguageTag("en")).build()

        assertEquals(
            TurnInfo.builder(TurnId("t2"), chat, ConversationId("c2"), TurnKind.MESSAGE).actor(operator)
                .language(LanguageTag("en")).build(),
            turn.toBuilder(TurnId("t2"), ConversationId("c2")).build(),
        )
        assertFailsWith<IllegalArgumentException> {
            TurnLineage(RunId("r"), TurnId("p"), ConversationId("c"), null, TurnId("p"), ConversationId("c"), 0)
        }
    }

    // Quotes.

    @Test
    fun `a quote is untrusted unless the channel says otherwise`() {
        val quote = Quote.builder("hello").sender(operator.address).build()

        assertEquals(QuoteTrust.UNTRUSTED, quote.trust)
        assertFalse(quote.external)
        assertFailsWith<IllegalArgumentException> { QuoteTrust(live = false, content = true) }
    }

    // Value types.

    @Test
    fun `ids are validated`() {
        assertEquals("main", AgentId.MAIN.value)
        for (make in listOf({
            AgentId("Main")
        }, { ConversationId("") }, { TurnId("") }, { RunId("") }, { ToolCallId("") })) {
            assertFailsWith<IllegalArgumentException> { make() }
        }
        assertFailsWith<IllegalArgumentException> { ChannelMessageRef(chat, "") }
    }

    @Test
    fun `a language tag is canonical BCP 47`() {
        assertEquals(LanguageTag("zh-CN"), LanguageTag.of("zh_cn"))
        assertEquals(LanguageTag("zh-Hans-CN"), LanguageTag.of(" zh-hans-cn "))
        assertEquals("\"en-US\"", Json.encodeToString(LanguageTag.of("en-us")))
        for (text in listOf("zh-cn", "", "en_US")) assertFailsWith<IllegalArgumentException>(text) { LanguageTag(text) }
        for (text in listOf("", "not a tag", "x")) {
            assertFailsWith<IllegalArgumentException>(text) {
                LanguageTag.of(text)
            }
        }
    }
}
