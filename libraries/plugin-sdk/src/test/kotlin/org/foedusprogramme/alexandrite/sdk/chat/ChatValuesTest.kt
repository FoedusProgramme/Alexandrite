package org.foedusprogramme.alexandrite.sdk.chat

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatValuesTest {
    private val work = ChannelInstanceId(ChannelType("telegram"), "work")
    private val chat = ChatAddress(work, "-100")
    private val admin = ChatUser(UserAddress(work, "1"), "Ada", "ada", isBot = false, isAdmin = true)

    private fun turn(): TurnInfo.Builder = TurnInfo.builder(TurnId("t1"), chat, ConversationId("c1"), TurnKind.MESSAGE)

    // Turn identity.

    @Test
    fun `a turn defaults to the main agent, the chat as reply target, no principal and no lineage`() {
        val turn = turn().build()

        assertEquals(AgentId.MAIN, turn.agent)
        assertEquals(ReplyTarget.CHAT, turn.replyTarget)
        assertNull(turn.actor)
        assertNull(turn.lineage)
        assertFalse(turn.actorIsAdmin)
        assertTrue(turn().actor(admin).build().actorIsAdmin)
    }

    @Test
    fun `a rebuilt turn keeps what the block leaves alone`() {
        val lineage = TurnLineage(
            RunId("r1"),
            TurnId("t0"),
            ConversationId("c0"),
            ToolCallId("call-1"),
            TurnId("t0"),
            ConversationId("c0"),
            depth = 1,
        )
        val turn = turn().actor(admin).language(LanguageTag("en")).build()

        val delegated = turn.rebuild {
            kind(TurnKind.DELEGATED)
            lineage(lineage)
            replyTarget(ReplyTarget.CALLER)
        }

        assertEquals(turn, turn.toBuilder().build())
        assertEquals(
            turn().actor(admin).language(LanguageTag("en")).kind(TurnKind.DELEGATED).lineage(lineage)
                .replyTarget(ReplyTarget.CALLER).build(),
            delegated,
        )
        assertEquals(
            "TurnInfo(id=t1, chat=telegram:work:-100, conversation=c1, kind=message, actor=null, language=null, " +
                "agent=main, lineage=null, replyTarget=chat)",
            turn().build().toString(),
        )
        assertFailsWith<IllegalArgumentException> {
            TurnLineage(RunId("r"), TurnId("p"), ConversationId("c"), null, TurnId("p"), ConversationId("c"), 0)
        }
    }

    // Quotes.

    @Test
    fun `a quote is untrusted unless the channel says otherwise`() {
        val quote = Quote.builder("hello").sender(admin.address).build()

        assertEquals(QuoteTrust.UNTRUSTED, quote.trust)
        assertFalse(quote.external)
        assertEquals(quote, quote.toBuilder().build())
        assertEquals("bye", quote.rebuild { text("bye") }.text)
        assertEquals(admin.address, quote.rebuild { text("bye") }.sender)
        assertFailsWith<IllegalArgumentException> { QuoteTrust(live = false, content = true) }
    }

    // Open enumerations.

    @Test
    fun `an open enumeration keeps an id it does not know`() {
        val unknown = Json.decodeFromString<TurnKind>("\"isolated\"")

        assertEquals("isolated", unknown.id)
        assertNotEquals(TurnKind.MESSAGE, unknown)
        assertEquals("\"isolated\"", Json.encodeToString(unknown))
        assertEquals(TurnKind.AGENT_MESSAGE, Json.decodeFromString<TurnKind>("\"agent_message\""))
        assertEquals("\"caller\"", Json.encodeToString(ReplyTarget.CALLER))
        assertEquals(ChatKind.DIRECT, Json.decodeFromString<ChatKind>("\"direct\""))
        assertEquals(ForwardKind.USER, Json.decodeFromString<ForwardKind>("\"user\""))
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

    @Test
    fun `values are equal by their properties and print them`() {
        val cases = listOf(
            Triple(
                { ChatUser(UserAddress(work, "1"), "Ada", null, isBot = false, isAdmin = false) },
                ChatUser(UserAddress(work, "1"), "Ada", null, isBot = false, isAdmin = true),
                "ChatUser(address=telegram:work@1, displayName=Ada, username=null, isBot=false, isAdmin=false)",
            ),
            Triple(
                { ChatInfo(ChatKind.GROUP, "Team", null) },
                ChatInfo(ChatKind.DIRECT, "Team", null),
                "ChatInfo(kind=group, title=Team, username=null)",
            ),
            Triple(
                { ForwardOrigin(ForwardKind.CHAT, "News", null, chat) },
                ForwardOrigin(ForwardKind.UNKNOWN, "News", null, chat),
                "ForwardOrigin(kind=chat, name=News, user=null, chat=telegram:work:-100)",
            ),
            Triple(
                { ChannelMessageRef(chat, "7") },
                ChannelMessageRef(chat, "8"),
                "ChannelMessageRef(chat=telegram:work:-100, id=7)",
            ),
            Triple({ ChatAddress(work, "-100", "7") }, ChatAddress(work, "-100"), "telegram:work:-100#7"),
        )
        for ((make, differing, printed) in cases) {
            assertEquals(make(), make())
            assertEquals(make().hashCode(), make().hashCode(), printed)
            assertNotEquals(make(), differing)
            assertEquals(printed, make().toString())
            assertTrue(make().javaClass.methods.none { it.name == "copy" || it.name.startsWith("component") }, printed)
        }
    }
}
