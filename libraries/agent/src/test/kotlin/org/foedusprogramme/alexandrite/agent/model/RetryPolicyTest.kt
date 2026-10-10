package org.foedusprogramme.alexandrite.agent.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.agent.config.ModelCallConfig
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RetryPolicyTest {
    private val turn = TurnId("t-1")

    private fun policy(retries: Int = 3, maxRetryAfterSeconds: Long = 60, jitter: Double = 0.5) =
        RetryPolicy(ModelCallConfig(retries, maxRetryAfterSeconds), FixedRandom(jitter))

    private fun error(
        kind: ModelErrorKind = ModelErrorKind.OVERLOADED,
        retryAfter: Duration? = null,
        outputStarted: Boolean = false,
    ): ModelError = ModelError.builder(kind, "Scripted.").retryAfter(retryAfter).outputStarted(outputStarted).build()

    @Test
    fun `a failure that may pass is retried after 1 s, then 4 s, until the call succeeds`() = runTest {
        val calls = mutableListOf<Long>()

        val result = policy().retrying(turn) {
            calls += testScheduler.currentTime
            if (calls.size < 3) throw ModelException(error()) else "Hello"
        }

        assertEquals("Hello", result)
        assertEquals(listOf(0L, 1_000L, 5_000L), calls)
    }

    @Test
    fun `the retries run out after 1 s, 4 s and 10 s, and the last failure is thrown`() = runTest {
        val calls = mutableListOf<Long>()
        val last = ModelException(error(ModelErrorKind.TIMEOUT))

        val thrown = assertFailsWith<ModelException> {
            policy().retrying(turn) {
                calls += testScheduler.currentTime
                throw if (calls.size == 4) last else ModelException(error())
            }
        }

        assertSame(last, thrown)
        assertEquals(listOf(0L, 1_000L, 5_000L, 15_000L), calls)
    }

    @Test
    fun `a failure after output started, a failure that cannot pass and no retries at all are thrown at once`() =
        runTest {
            val failures = listOf(
                policy() to error(outputStarted = true),
                policy() to error(ModelErrorKind.AUTHENTICATION),
                policy() to ModelError.builder(ModelErrorKind.OVERLOADED, "Scripted.").retryable(false).build(),
                policy(retries = 0) to error(),
            )
            for ((policy, error) in failures) {
                var calls = 0

                assertFailsWith<ModelException> {
                    policy.retrying(turn) {
                        calls++
                        throw ModelException(error)
                    }
                }

                assertEquals(1, calls, "$error")
                assertEquals(0L, testScheduler.currentTime)
            }
        }

    @Test
    fun `a wait the backend asks for is kept, up to the longest one allowed`() = runTest {
        val calls = mutableListOf<Long>()

        policy(maxRetryAfterSeconds = 30).retrying(turn) {
            calls += testScheduler.currentTime
            when (calls.size) {
                1 -> throw ModelException(error(ModelErrorKind.RATE_LIMITED, retryAfter = 7.seconds))
                2 -> throw ModelException(error(retryAfter = 30.seconds))
                else -> Unit
            }
        }

        assertEquals(listOf(0L, 7_000L, 37_000L), calls)
        assertNull(policy(maxRetryAfterSeconds = 30).delayBefore(1, error(retryAfter = 31.seconds)))
        assertEquals(Duration.ZERO, policy().delayBefore(1, error(retryAfter = Duration.ZERO)))
    }

    @Test
    fun `the backoff varies by a fifth either way and stays at 10 s after the third retry`() {
        assertEquals(800.milliseconds, policy(retries = 5, jitter = 0.0).delayBefore(1, error()))
        assertEquals(1_200.milliseconds, policy(retries = 5, jitter = 1.0).delayBefore(1, error()))
        assertEquals(3_200.milliseconds, policy(retries = 5, jitter = 0.0).delayBefore(2, error()))
        assertEquals(10.seconds, policy(retries = 5).delayBefore(5, error()))
        assertNull(policy(retries = 5).delayBefore(6, error()))
    }
}

/** A random source whose doubles are all [value]. */
private class FixedRandom(private val value: Double) : Random() {
    override fun nextBits(bitCount: Int): Int = 0

    override fun nextDouble(): Double = value
}
