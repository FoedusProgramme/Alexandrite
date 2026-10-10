package org.foedusprogramme.alexandrite.channel.common.pairing

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.testkit.testUser
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingsTest {
    private val clock = TestClock()
    private val desk = Pairings(clock)
    private val work = ChannelInstanceId(ChannelType("chat"), "work")
    private val home = ChannelInstanceId(ChannelType("chat"), "home")
    private val configured = ChatAddress(work, "7")
    private val stranger = ChatAddress(work, "42")
    private val group = ChatAddress(work, "-100")
    private val ada = testUser("ada", instance = work)
    private val store = MemoryStore(configured)
    private val pairing = desk.open(work, listOf(configured), store)

    private fun offer(chat: ChatAddress = stranger, pairing: InstancePairing = this.pairing): PairingCode? =
        pairing.request(chat, ChatKind.DIRECT, null, ada)

    // Codes.

    @Test
    fun `a paired chat and its threads pass and are offered no code`() {
        assertTrue(pairing.isPaired(configured))
        assertTrue(pairing.isPaired(ChatAddress(work, "7", "3")))
        assertNull(offer(configured))
        assertEquals(emptyList(), desk.pending())
        assertEquals(listOf(configured), desk.paired())
    }

    @Test
    fun `an unpaired chat is offered one code per code lifetime, then nothing until it expires`() {
        val first = assertNotNull(offer())

        assertFalse(pairing.isPaired(stranger))
        assertTrue(Regex("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}").matches(first.text), first.text)
        assertEquals(clock.now.plusSeconds(600), first.expiresAt)
        clock.advance(599)
        assertNull(offer())
        assertNull(offer(ChatAddress(work, "42", "9")))
        clock.advance(1)
        val second = assertNotNull(offer())
        assertNotEquals(first.text, second.text)
        assertEquals(listOf(second.text), desk.pending().map { it.code })
    }

    @Test
    fun `a request names the chat, its kind and title, who asked and when the code expires`() {
        val code = assertNotNull(pairing.request(ChatAddress(work, "-100", "5"), ChatKind.GROUP, "Backend", ada))

        assertEquals(
            listOf(PairingRequest(group, ChatKind.GROUP, "Backend", ada, code.text, clock.now, code.expiresAt)),
            desk.pending(),
        )
    }

    @Test
    fun `the requests come oldest first and leave when their codes expire`() {
        offer(group)
        clock.advance(300)
        offer()

        assertEquals(listOf(group, stranger), desk.pending().map { it.chat })
        clock.advance(300)
        assertEquals(listOf(stranger), desk.pending().map { it.chat })
        clock.advance(300)
        assertEquals(emptyList(), desk.pending())
    }

    // Decisions.

    @Test
    fun `an approved code pairs its chat at once and keeps it in the store`() = runTest {
        val code = assertNotNull(offer())

        val decision = assertIs<PairingDecision.Approved>(desk.approve(code.text.lowercase().replace("-", " ")))

        assertEquals(stranger, decision.request.chat)
        assertTrue(pairing.isPaired(stranger))
        assertEquals(listOf(configured, stranger), store.chats)
        assertEquals(listOf(configured, stranger), desk.paired())
        assertEquals(emptyList(), desk.pending())
        assertIs<PairingDecision.Unknown>(desk.approve(code.text))
        assertNull(offer())
    }

    @Test
    fun `a denied code pairs nothing and the chat stays silent until the code would have expired`() = runTest {
        val code = assertNotNull(offer())

        assertEquals(stranger, assertIs<PairingDecision.Denied>(desk.deny(code.text)).request.chat)

        assertFalse(pairing.isPaired(stranger))
        assertEquals(listOf(configured), store.chats)
        assertEquals(emptyList(), desk.pending())
        assertNull(offer())
        clock.advance(600)
        assertNotNull(offer())
    }

    @Test
    fun `unknown, malformed and too many wrong codes are told apart`() = runTest {
        assertEquals(PairingDecision.Malformed, desk.approve("hello"))
        assertEquals(PairingDecision.Unknown(4), desk.approve("AAAA-AAAA"))
        repeat(3) { desk.deny("AAAA-AAAA") }
        assertEquals(PairingDecision.Unknown(0), desk.deny("AAAA-AAAA"))
        val code = assertNotNull(offer())

        val locked = assertIs<PairingDecision.Locked>(desk.approve("AAAA-AAAA"))

        assertEquals(clock.now.plusSeconds(600), locked.until)
        assertIs<PairingDecision.Locked>(desk.approve(code.text))
        assertFalse(pairing.isPaired(stranger))
    }

    @Test
    fun `a store that fails keeps the chat unpaired and its request waiting under a new code`() = runTest {
        val code = assertNotNull(offer())
        store.failure = IOException("The disk is full.")

        val failed = assertIs<PairingDecision.Failed>(desk.approve(code.text))

        assertEquals("The disk is full.", failed.reason)
        assertNotEquals(code.text, failed.request.code)
        assertFalse(pairing.isPaired(stranger))
        assertEquals(listOf(failed.request), desk.pending())
        assertNull(offer())
        assertIs<PairingDecision.Unknown>(desk.approve(code.text))
        store.failure = null
        assertIs<PairingDecision.Approved>(desk.approve(failed.request.code))
        assertTrue(pairing.isPaired(stranger))
    }

    @Test
    fun `unpairing takes the chat out of the store and stops its messages at once`() = runTest {
        assertEquals(Unpairing.Unpaired, desk.unpair(ChatAddress(work, "7", "3")))

        assertFalse(pairing.isPaired(configured))
        assertEquals(emptyList(), store.chats)
        assertEquals(Unpairing.NotPaired, desk.unpair(configured))
        assertEquals(Unpairing.NotPaired, desk.unpair(ChatAddress(home, "7")))
        assertNotNull(offer(configured))
    }

    @Test
    fun `a chat that only a reference keeps paired, or a store that fails, stays paired`() = runTest {
        store.chats.clear()
        store.referenced += configured

        assertEquals(Unpairing.ByReference, desk.unpair(configured))
        assertTrue(pairing.isPaired(configured))

        store.failure = IOException("The file is read-only.")
        assertEquals(Unpairing.Failed("The file is read-only."), desk.unpair(configured))
        assertTrue(pairing.isPaired(configured))
    }

    // Instances.

    @Test
    fun `one desk pairs the chats of every instance it opened`() = runTest {
        val other = desk.open(home, emptyList(), MemoryStore())
        val homeChat = ChatAddress(home, "42")
        val code = assertNotNull(other.request(homeChat, ChatKind.DIRECT, null, null))
        assertNotNull(offer())

        assertIs<PairingDecision.Approved>(desk.approve(code.text))

        assertTrue(other.isPaired(homeChat))
        assertFalse(pairing.isPaired(stranger))
        assertEquals(listOf(configured, homeChat), desk.paired())
        assertEquals(listOf(stranger), desk.pending().map { it.chat })
    }

    @Test
    fun `a closed instance takes its requests and codes along, and may open again`() = runTest {
        val code = assertNotNull(offer())

        desk.close(work)

        assertEquals(emptyList(), desk.pending())
        assertEquals(emptyList(), desk.paired())
        assertIs<PairingDecision.Unknown>(desk.approve(code.text))
        assertNull(offer())
        val reopened = desk.open(work, emptyList(), MemoryStore())
        assertNotNull(offer(pairing = reopened))
    }

    @Test
    fun `an instance opens once, with its own chats only`() {
        assertFailsWith<IllegalArgumentException> { desk.open(work, emptyList(), MemoryStore()) }
        assertFailsWith<IllegalArgumentException> { desk.open(home, listOf(configured), MemoryStore()) }
    }
}
