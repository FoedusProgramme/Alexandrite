package org.foedusprogramme.alexandrite.internal.pairing

import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class PairingCodesTest {
    private val clock = TestClock()
    private val codes = PairingCodes<String, String>(clock)

    private fun scripted(vararg drawn: String) =
        PairingCodes<String, String>(clock, random = ScriptedRandom(drawn.toList()))

    private fun PairingCodes<String, String>.assertRejected(
        input: String,
        attempter: String = "b",
        triesLeft: Int = 4,
        purpose: String = LINK,
    ) = assertEquals(triesLeft, assertIs<Redemption.Rejected>(redeem(purpose, input, attempter)).triesLeft)

    private fun PairingCodes<String, String>.assertRedeemed(
        input: String,
        owner: String = "a",
        payload: String = "offer",
        attempter: String = "b",
        purpose: String = LINK,
    ) {
        val redeemed = assertIs<Redemption.Redeemed<String, String>>(redeem(purpose, input, attempter))
        assertEquals(owner to payload, redeemed.owner to redeemed.payload)
    }

    private fun PairingCodes<String, String>.assertLocked(until: Instant, input: String, attempter: String = "b") =
        assertEquals(until, assertIs<Redemption.Locked>(redeem(LINK, input, attempter)).until)

    private fun advance(duration: Duration) {
        clock.now += duration.toJavaDuration()
    }

    // Codes.

    @Test
    fun `a code is eight characters of the alphabet shown in two groups and lives ten minutes`() {
        val issued = codes.issue(LINK, "a", "offer")

        assertTrue(Regex("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}").matches(issued.text), issued.text)
        assertEquals(clock.now + 10.minutes.toJavaDuration(), issued.expiresAt)
    }

    @Test
    fun `the forty bits drawn make the code`() {
        val drawn = listOf("00000000", "ZZZZZZZZ", "0123ABCD", "VWXYZ789")
        val scripted = scripted(*drawn.toTypedArray())

        val texts = drawn.indices.map { scripted.issue(LINK, "owner $it", "offer").text }

        assertEquals(listOf("0000-0000", "ZZZZ-ZZZZ", "0123-ABCD", "VWXY-Z789"), texts)
    }

    @Test
    fun `a code equal to a live code is drawn again`() {
        val scripted = scripted("7K3M9QXR", "7K3M9QXR", "AB12CD34")

        val first = scripted.issue(LINK, "a", "first").text
        val second = scripted.issue(LINK, "c", "second").text

        assertEquals(listOf("7K3M-9QXR", "AB12-CD34"), listOf(first, second))
        scripted.assertRedeemed("7K3M9QXR", payload = "first")
        scripted.assertRedeemed("AB12CD34", owner = "c", payload = "second")
    }

    // Input.

    @Test
    fun `input is read whatever its case, hyphens and spaces, with look-alike letters read as digits`() {
        val inputs = listOf("1A0Z-9KMX", "1a0z9kmx", "  la0z 9kmx\t", "IA0Z-9KMX", "iAOz - 9kMx", "1-a-0-z-9-k-m-x")
        val scripted = scripted(*Array(inputs.size) { "1A0Z9KMX" })

        for (input in inputs) {
            assertEquals("1A0Z-9KMX", scripted.issue(LINK, "a", "offer").text)
            scripted.assertRedeemed(input)
        }
    }

    @Test
    fun `input that holds no eight code characters is malformed and not counted`() {
        val issued = codes.issue(LINK, "a", "offer")
        val malformed = listOf(
            "", "   ", "-", "ABCD-EFG", "ABCD-EFGHJ", "UBCD-EFGH", "ABCD_EFGH", "ABCD.EFGH", "ABCD\tEFGH", "ıBCD-EFGH",
            "ＡBCD-EFGH",
        )

        repeat(2) {
            for (input in malformed) assertEquals(Redemption.Malformed, codes.redeem(LINK, input, "b"), input)
        }

        codes.assertRedeemed(issued.text)
    }

    // Spending.

    @Test
    fun `a code is spent once, by another attempter`() {
        val issued = codes.issue(LINK, "a", "offer")

        codes.assertRedeemed(issued.text)
        codes.assertRejected(issued.text, attempter = "c")
    }

    @Test
    fun `the owner's own code is neither spent nor counted`() {
        val issued = codes.issue(LINK, "a", "offer")

        repeat(MAX_FAILURES + 1) { assertEquals(Redemption.OwnCode, codes.redeem(LINK, issued.text, "a")) }

        codes.assertRedeemed(issued.text)
    }

    @Test
    fun `a code lives until its expiry and is unknown from then on`() {
        val kept = codes.issue(LINK, "a", "kept")
        val lost = codes.issue(LINK, "c", "lost")

        advance(10.minutes - 1.milliseconds)
        codes.assertRedeemed(kept.text, payload = "kept")
        advance(1.milliseconds)

        assertEquals(lost.expiresAt, clock.now)
        codes.assertRejected(lost.text)
        assertEquals(false, codes.revoke(LINK, "c"))
    }

    @Test
    fun `codes of different purposes are apart`() {
        val link = codes.issue(LINK, "a", "link")
        val pair = codes.issue(PAIR, "a", "pair")

        codes.assertRejected(link.text, purpose = PAIR)
        codes.assertRedeemed(link.text, payload = "link")
        assertEquals(false, codes.revoke(LINK, "a"))
        codes.assertRedeemed(pair.text, payload = "pair", purpose = PAIR)
    }

    @Test
    fun `a new code replaces the one the owner holds for the purpose, and a revoked code is unknown`() {
        val first = codes.issue(LINK, "a", "first")
        val other = codes.issue(PAIR, "a", "other")
        val second = codes.issue(LINK, "a", "second")

        codes.assertRejected(first.text)
        codes.assertRedeemed(second.text, payload = "second")
        assertEquals(true, codes.revoke(PAIR, "a"))
        assertEquals(false, codes.revoke(PAIR, "a"))
        codes.assertRejected(other.text, purpose = PAIR)
    }

    @Test
    fun `a live code of the purpose is held however it is written, and asking is never counted`() {
        val issued = codes.issue(LINK, "a", "offer")
        val other = codes.issue(PAIR, "c", "other")

        assertTrue(codes.holds(LINK, issued.text.lowercase().replace("-", " ")))
        assertEquals(false, codes.holds(PAIR, issued.text))
        assertEquals(false, codes.holds(LINK, other.text))
        assertEquals(false, codes.holds(LINK, "hello"))
        repeat(MAX_FAILURES + 1) { assertEquals(false, codes.holds(LINK, "AAAA-AAAA")) }

        codes.assertRedeemed(issued.text)
        assertEquals(false, codes.holds(LINK, issued.text))
    }

    @Test
    fun `an expired code is no longer held`() {
        val issued = codes.issue(LINK, "a", "offer")

        advance(10.minutes - 1.milliseconds)
        assertTrue(codes.holds(LINK, issued.text))
        advance(1.milliseconds)

        assertEquals(false, codes.holds(LINK, issued.text))
    }

    @Test
    fun `a trusted redeemer spends a live code of the purpose once, and its wrong codes never lock`() {
        val issued = codes.issue(LINK, "a", "offer")
        val other = codes.issue(PAIR, "c", "other")

        repeat(MAX_FAILURES * 3) { assertEquals(null, codes.take(LINK, "WRNG-C0DE")) }
        assertEquals(null, codes.take(LINK, other.text))
        assertEquals(null, codes.take(LINK, "hello"))
        val taken = codes.take(LINK, issued.text.lowercase().replace("-", " "))

        assertEquals("a" to "offer", taken?.owner to taken?.payload)
        assertEquals(null, codes.take(LINK, issued.text))
        codes.assertRedeemed(other.text, owner = "c", payload = "other", purpose = PAIR)
    }

    @Test
    fun `input is a code when it holds eight code characters, however it is written`() {
        for (input in listOf("1A0Z-9KMX", "  la0z 9kmx\t", "WRNG-C0DE")) assertTrue(isPairingCode(input), input)
        for (input in listOf("", "hello", "ABCD-EFG", "UBCD-EFGH")) assertEquals(false, isPairingCode(input), input)
    }

    @Test
    fun `beyond the capacity the oldest code is dropped`() {
        val small = PairingCodes<String, String>(clock, capacity = 3)
        val issued = (1..4).map { small.issue(LINK, "owner $it", "offer $it") }

        small.assertRejected(issued[0].text)
        for (index in 1..3) small.assertRedeemed(issued[index].text, "owner ${index + 1}", "offer ${index + 1}", "c")
    }

    // Locks.

    @Test
    fun `wrong codes lock the attempter, even against a right code, until its oldest failure leaves the window`() {
        val issued = codes.issue(LINK, "a", "offer")
        val first = clock.now

        for (triesLeft in MAX_FAILURES - 1 downTo 0) {
            codes.assertRejected("WRNG-C0DE", triesLeft = triesLeft)
            advance(1.minutes)
        }
        codes.assertLocked(first + 10.minutes.toJavaDuration(), input = issued.text)
        codes.assertLocked(first + 10.minutes.toJavaDuration(), input = "not a code")
        advance(5.minutes - 1.seconds)
        codes.assertLocked(first + 10.minutes.toJavaDuration(), input = issued.text)
        advance(1.seconds)

        codes.assertRedeemed(codes.issue(LINK, "a", "offer").text)
        codes.assertRejected("WRNG-C0DE")
    }

    @Test
    fun `failures older than the window no longer count`() {
        repeat(MAX_FAILURES - 1) { codes.assertRejected("WRNG-C0DE", triesLeft = MAX_FAILURES - 1 - it) }
        advance(10.minutes)

        codes.assertRejected("WRNG-C0DE")
    }

    @Test
    fun `a lock holds one attempter for one purpose`() {
        repeat(MAX_FAILURES) { codes.assertRejected("WRNG-C0DE", triesLeft = MAX_FAILURES - 1 - it) }
        val link = codes.issue(LINK, "a", "offer")
        val pair = codes.issue(PAIR, "a", "pair")

        assertIs<Redemption.Locked>(codes.redeem(LINK, link.text, "b"))
        codes.assertRedeemed(pair.text, payload = "pair", purpose = PAIR)
        codes.assertRedeemed(link.text, attempter = "c")
    }

    @Test
    fun `the failures of the least recently seen attempters are dropped beyond the limit`() {
        val limit = PairingCodes.ATTEMPTERS
        repeat(MAX_FAILURES) { codes.assertRejected("WRNG-C0DE", triesLeft = MAX_FAILURES - 1 - it) }
        val until = clock.now + 10.minutes.toJavaDuration()
        for (index in 1 until limit) codes.assertRejected("WRNG-C0DE", attempter = "first $index")

        codes.assertLocked(until, "WRNG-C0DE")
        codes.assertRejected("WRNG-C0DE", attempter = "extra")
        codes.assertLocked(until, "WRNG-C0DE")
        repeat(limit) { codes.assertRejected("WRNG-C0DE", attempter = "second $it") }

        codes.assertRejected("WRNG-C0DE")
    }

    // Threads.

    @Test
    fun `a code is spent once however many threads redeem it`() {
        val issued = codes.issue(LINK, "a", "offer")

        val results = parallel(32) { codes.redeem(LINK, issued.text, "attempter $it") }

        assertEquals(1, results.count { it is Redemption.Redeemed<*, *> })
        assertEquals(31, results.count { it is Redemption.Rejected })
    }

    @Test
    fun `codes issued from many threads at once are all distinct and live`() {
        val issued = parallel(64) { codes.issue(LINK, "owner $it", "offer $it") }

        assertEquals(64, issued.map { it.text }.distinct().size)
        issued.forEachIndexed { index, code -> codes.assertRedeemed(code.text, "owner $index", "offer $index") }
    }

    // Settings.

    @Test
    fun `the durations and counts are positive`() {
        val invalid = listOf<() -> Unit>(
            { PairingCodes<String, String>(clock, ttl = Duration.ZERO) },
            { PairingCodes<String, String>(clock, ttl = Duration.INFINITE) },
            { PairingCodes<String, String>(clock, failureWindow = (-1).seconds) },
            { PairingCodes<String, String>(clock, maxFailures = 0) },
            { PairingCodes<String, String>(clock, capacity = 0) },
        )

        for (create in invalid) assertFailsWith<IllegalArgumentException> { create() }
    }

    private fun <T> parallel(count: Int, action: (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        return Executors.newFixedThreadPool(count).use { executor ->
            val futures = List(count) { index ->
                executor.submit<T> {
                    start.await()
                    action(index)
                }
            }
            start.countDown()
            futures.map { it.get() }
        }
    }

    private class TestClock(var now: Instant = Instant.parse("2026-10-11T12:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private class ScriptedRandom(codes: List<String>) : SecureRandom() {
        private val codes = ArrayDeque(codes)

        override fun nextBytes(bytes: ByteArray) {
            val bits = codes.removeFirst().fold(0L) { value, char ->
                (value shl 5) or PairingCodes.ALPHABET.indexOf(char).toLong()
            }
            for (index in bytes.indices) bytes[index] = (bits shr (bytes.size - 1 - index) * 8).toByte()
        }
    }

    private companion object {
        const val LINK = "link"
        const val PAIR = "pair"
        const val MAX_FAILURES = 5
    }
}
