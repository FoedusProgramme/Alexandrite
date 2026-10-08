package org.foedusprogramme.alexandrite.testkit.store

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

internal fun fail(message: String, cause: Throwable? = null): Nothing = throw AssertionError(message, cause)

internal fun expect(condition: Boolean, message: () -> String) {
    if (!condition) fail(message())
}

internal fun <T> expectEqual(expected: T, actual: T, what: String) {
    if (expected != actual) fail("$what: expected <$expected>, was <$actual>.")
}

/** Expects [time] between [from], to the millisecond, and [to]. */
internal fun expectWithin(time: Instant?, from: Instant, to: Instant, what: String) {
    expect(time != null && time >= from.truncatedTo(ChronoUnit.MILLIS) && time <= to) {
        "$what: expected a time from $from to $to, was $time."
    }
}

internal inline fun <reified E : Throwable> expectThrows(what: String, block: () -> Unit): E {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (e is E) return e
        fail("$what: expected ${E::class.java.simpleName}, was $e.", e)
    }
    fail("$what: expected ${E::class.java.simpleName}, but nothing was thrown.")
}
