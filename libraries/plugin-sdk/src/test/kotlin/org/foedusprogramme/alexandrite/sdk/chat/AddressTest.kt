package org.foedusprogramme.alexandrite.sdk.chat

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AddressTest {
    private val work = ChannelInstanceId(ChannelType("telegram"), "work")

    // Chat addresses.

    @Test
    fun `a chat address prints its instance, chat and thread`() {
        assertEquals("telegram:work:-1001234567890#42", ChatAddress(work, "-1001234567890", "42").toString())
        assertEquals("telegram:work:-1001234567890", ChatAddress(work, "-1001234567890").toString())
        assertEquals("telegram:work:a%23b%25c#t%231:%25", ChatAddress(work, "a#b%c", "t#1:%").toString())
    }

    @Test
    fun `parsing splits at the first two colons and the first number sign`() {
        assertEquals(
            ChatAddress(ChannelInstanceId(ChannelType("matrix"), "home"), "!room:example.org", "\$event:example.org"),
            ChatAddress.parse("matrix:home:!room:example.org#\$event:example.org"),
        )
        assertEquals(
            ChatAddress(ChannelInstanceId(ChannelType("onebot"), "qq"), "group:123"),
            ChatAddress.parse("onebot:qq:group:123"),
        )
        assertEquals(ChatAddress(work, "a#b%c", "t#1"), ChatAddress.parse("telegram:work:a%23b%25c#t%231"))
    }

    @Test
    fun `only canonical text parses`() {
        val malformed = listOf(
            "",
            "telegram",
            "telegram:work",
            "telegram:work:",
            "Telegram:work:1",
            "telegram:Work:1",
            "tele gram:work:1",
            ":work:1",
            "telegram::1",
            "telegram:work:1#",
            "telegram:work:#2",
            "telegram:work:1#a#b",
            "telegram:work:1%",
            "telegram:work:1%2",
            "telegram:work:1%41",
            "telegram:work:1%2a",
            "telegram:work:a\nb",
        )
        for (text in malformed) {
            assertNull(ChatAddress.parseOrNull(text), text)
            assertFailsWith<IllegalArgumentException>(text) { ChatAddress.parse(text) }
        }
    }

    @Test
    fun `a chat address needs non-empty ids without control characters`() {
        for (chat in listOf("", "a\u0000b", "tab\t")) {
            assertFailsWith<IllegalArgumentException>(chat) { ChatAddress(work, chat) }
            assertFailsWith<IllegalArgumentException>(chat) { ChatAddress(work, "1", chat) }
        }
    }

    @Test
    fun `the parent of a thread is its chat`() {
        val chat = ChatAddress(work, "-100")

        assertEquals(chat, ChatAddress(work, "-100", "7").parent)
        assertSame(chat, chat.parent)
    }

    @Test
    fun `printing and parsing are inverse for random chat addresses`() {
        val random = Random(SEED)
        val printed = HashMap<String, ChatAddress>()
        repeat(RUNS) {
            val address = ChatAddress(random.instance(), random.id(), if (random.nextBoolean()) random.id() else null)
            val text = address.toString()

            assertEquals(address, ChatAddress.parse(text), text)
            assertEquals(address, printed.getOrPut(text) { address }, text)
        }
    }

    @Test
    fun `any text that parses as a chat address is its canonical form`() {
        val random = Random(SEED)
        repeat(RUNS) {
            val text = "${random.instance()}:" + random.text()
            ChatAddress.parseOrNull(text)?.let { assertEquals(text, it.toString()) }
        }
    }

    // User addresses.

    @Test
    fun `a user address splits at the first at sign after the instance`() {
        val home = ChannelInstanceId(ChannelType("matrix"), "home")

        assertEquals("matrix:home@@alice:example.org", UserAddress(home, "@alice:example.org").toString())
        assertEquals(UserAddress(home, "@alice:example.org"), UserAddress.parse("matrix:home@@alice:example.org"))
        assertEquals("telegram:work@50%25#1", UserAddress(work, "50%#1").toString())
        assertEquals(UserAddress(work, "50%#1"), UserAddress.parse("telegram:work@50%25#1"))
    }

    @Test
    fun `only canonical user addresses parse`() {
        for (text in listOf("", "telegram:work", "telegram:work@", "telegram@42", "telegram:work@4%2", "Tg:work@1")) {
            assertNull(UserAddress.parseOrNull(text), text)
        }
        assertFailsWith<IllegalArgumentException> { UserAddress(work, "") }
    }

    @Test
    fun `printing and parsing are inverse for random user addresses`() {
        val random = Random(SEED)
        repeat(RUNS) {
            val address = UserAddress(random.instance(), random.id())
            val text = address.toString()

            assertEquals(address, UserAddress.parse(text), text)
            val other = "${address.instance}@${random.text()}"
            UserAddress.parseOrNull(other)?.let { assertEquals(other, it.toString()) }
        }
    }

    // Instances and serialization.

    @Test
    fun `an instance id parses from its printed form`() {
        assertEquals(work, ChannelInstanceId.parse("telegram:work"))
        assertNull(ChannelInstanceId.parseOrNull("telegram:work:1"))
        assertFailsWith<IllegalArgumentException> { ChannelInstanceId(ChannelType("telegram"), "my_bot") }
        assertFailsWith<IllegalArgumentException> { ChannelType("Telegram") }
    }

    @Test
    fun `addresses serialize as their printed form`() {
        val ref = ChannelMessageRef(ChatAddress(work, "-100", "7"), "42")
        val json = """{"chat":"telegram:work:-100#7","id":"42"}"""

        assertEquals(json, Json.encodeToString(ref))
        assertEquals(ref, Json.decodeFromString<ChannelMessageRef>(json))
        assertEquals("\"telegram:work@1\"", Json.encodeToString(UserAddress(work, "1")))
        assertEquals(work, Json.decodeFromString<ChannelInstanceId>("\"telegram:work\""))
        assertFailsWith<SerializationException> { Json.decodeFromString<ChatAddress>("\"telegram:work\"") }
    }

    private fun Random.instance() = ChannelInstanceId(ChannelType(word()), word())

    private fun Random.word(): String = buildString {
        append(('a'..'z').random(this@word))
        repeat(nextInt(0, 6)) { append(ID_TAIL.random(this@word)) }
        if (nextInt(4) == 0) append('-').append(('a'..'z').random(this@word))
    }

    /** A chat, thread or user id, often holding the characters the addresses escape. */
    private fun Random.id(): String = buildString { repeat(nextInt(1, 12)) { append(ALPHABET.random(this@id)) } }

    /** Any text, escape sequences included. */
    private fun Random.text(): String = buildString {
        repeat(nextInt(0, 12)) {
            append(
                if (nextInt(5) ==
                    0
                ) {
                    ESCAPES.random(this@text)
                } else {
                    "${ALPHABET.random(this@text)}"
                },
            )
        }
    }

    private companion object {
        const val SEED = 20261007
        const val RUNS = 2000
        const val ID_TAIL = "abcxyz0189"
        const val ALPHABET = "aZ09:#%@!$._-/ é中😀"
        val ESCAPES = listOf("%23", "%25", "%2", "%41", "#", "%")
    }
}
