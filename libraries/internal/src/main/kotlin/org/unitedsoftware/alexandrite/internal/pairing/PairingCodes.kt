package org.unitedsoftware.alexandrite.internal.pairing

import org.unitedsoftware.alexandrite.internal.collection.BoundedMap
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** Single-use codes kept in memory: 8 Crockford Base32 characters, shown as `XXXX-XXXX`. */
public class PairingCodes<K : Any, P : Any>(
    private val clock: Clock,
    private val ttl: Duration = 10.minutes,
    /** Wrong codes per purpose and attempter within [failureWindow] before a lock. */
    private val maxFailures: Int = 5,
    private val failureWindow: Duration = 10.minutes,
    /** The live codes kept, dropping the oldest. */
    private val capacity: Int = 1_000,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private val codes = LinkedHashMap<String, Live<K, P>>()
    private val owned = HashMap<Pair<String, K>, String>()
    private val failures = BoundedMap<Pair<String, K>, MutableList<Instant>>(ATTEMPTERS)

    init {
        for ((name, value) in listOf("ttl" to ttl, "failure window" to failureWindow)) {
            require(value.isPositive() && value.isFinite()) { "The $name is positive and finite, was $value." }
        }
        require(maxFailures > 0) { "The failures before a lock are positive, were $maxFailures." }
        require(capacity > 0) { "The capacity is positive, was $capacity." }
    }

    /** A new code for [purpose] carrying [payload], which replaces the code [owner] holds for [purpose]. */
    public fun issue(purpose: String, owner: K, payload: P): IssuedCode {
        synchronized(lock) {
            val now = clock.instant()
            purge(now)
            owned[purpose to owner]?.let(::remove)
            val code = generateSequence(::draw).first { it !in codes }
            val live = Live(purpose, owner, payload, now + ttl.toJavaDuration())
            codes[code] = live
            owned[purpose to owner] = code
            while (codes.size > capacity) remove(codes.keys.first())
            return IssuedCode("${code.take(GROUP)}-${code.drop(GROUP)}", live.expiresAt)
        }
    }

    /** Spends the code [input] for [purpose] on behalf of [attempter], or says why not. */
    public fun redeem(purpose: String, input: String, attempter: K): Redemption<K, P> {
        synchronized(lock) {
            val now = clock.instant()
            purge(now)
            val key = purpose to attempter
            val failed = failures[key]?.apply { removeAll { !now.isBefore(it + failureWindow.toJavaDuration()) } }
            if (failed?.isEmpty() == true) failures.remove(key)
            if (failed != null && failed.size >= maxFailures) {
                return Redemption.Locked(failed.min() + failureWindow.toJavaDuration())
            }
            val code = normalize(input) ?: return Redemption.Malformed
            val live = codes[code]?.takeIf { it.purpose == purpose }
            if (live == null) {
                val counted = failures.getOrPut(key) { mutableListOf() }.apply { add(now) }
                return Redemption.Rejected(maxFailures - counted.size)
            }
            if (live.owner == attempter) return Redemption.OwnCode
            remove(code)
            failures.remove(key)
            return Redemption.Redeemed(live.owner, live.payload)
        }
    }

    /** Spends the live code [input] for [purpose] without counting a wrong one, null when [input] is no live code. */
    public fun take(purpose: String, input: String): Redemption.Redeemed<K, P>? {
        synchronized(lock) {
            purge(clock.instant())
            val code = normalize(input) ?: return null
            val live = codes[code]?.takeIf { it.purpose == purpose } ?: return null
            remove(code)
            return Redemption.Redeemed(live.owner, live.payload)
        }
    }

    /** Whether [input] is a live code for [purpose], which is neither spent nor counted. */
    public fun holds(purpose: String, input: String): Boolean {
        synchronized(lock) {
            purge(clock.instant())
            val code = normalize(input) ?: return false
            return codes[code]?.purpose == purpose
        }
    }

    /** Revokes the code [owner] holds for [purpose], false when it holds none. */
    public fun revoke(purpose: String, owner: K): Boolean {
        synchronized(lock) {
            purge(clock.instant())
            val code = owned[purpose to owner] ?: return false
            remove(code)
            return true
        }
    }

    private fun purge(now: Instant) {
        codes.filterValues { !now.isBefore(it.expiresAt) }.keys.forEach(::remove)
    }

    private fun remove(code: String) {
        codes.remove(code)?.let { owned.remove(it.purpose to it.owner) }
    }

    /** 40 random bits as 8 code characters. */
    private fun draw(): String {
        val bytes = ByteArray(5).also(random::nextBytes)
        val bits = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
        return String(CharArray(LENGTH) { index -> ALPHABET[(bits shr (LENGTH - 1 - index) * 5).toInt() and 31] })
    }

    private class Live<K, P>(val purpose: String, val owner: K, val payload: P, val expiresAt: Instant)

    internal companion object {
        /** The attempters whose failures are kept, dropping the least recently seen. */
        const val ATTEMPTERS = 4096

        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val LENGTH = 8
        const val GROUP = 4
    }
}

/** Whether [input] holds the eight characters of a code, however it is written. */
public fun isPairingCode(input: String): Boolean = normalize(input) != null

/** The code characters of [input], null when it holds no code. */
private fun normalize(input: String): String? {
    val code = StringBuilder()
    for (char in input.trim()) {
        when (char) {
            '-', ' ' -> {}
            'I', 'i', 'L', 'l' -> code.append('1')
            'O', 'o' -> code.append('0')
            in 'a'..'z' -> code.append(char.uppercaseChar())
            else -> code.append(char)
        }
    }
    return code.toString().takeIf { text ->
        text.length == PairingCodes.LENGTH && text.all { it in PairingCodes.ALPHABET }
    }
}

/** A code [PairingCodes.issue] gave out. */
public class IssuedCode(public val text: String, public val expiresAt: Instant)

/** What [PairingCodes.redeem] made of a code. */
public sealed interface Redemption<out K, out P> {
    public class Redeemed<K, P>(public val owner: K, public val payload: P) : Redemption<K, P>

    /** An unknown, spent or expired code, or one of another purpose. */
    public class Rejected(public val triesLeft: Int) : Redemption<Nothing, Nothing>

    /** Too many wrong codes. */
    public class Locked(public val until: Instant) : Redemption<Nothing, Nothing>

    /** The attempter's own live code, neither spent nor counted. */
    public data object OwnCode : Redemption<Nothing, Nothing>

    /** Uncounted input that holds no code. */
    public data object Malformed : Redemption<Nothing, Nothing>
}
