package org.foedusprogramme.alexandrite.agent.model

import kotlinx.coroutines.delay
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.config.ModelCallConfig
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Inject
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Calls a model again after a failure that may pass, as long as the failed call showed nothing. */
@Singleton
internal class RetryPolicy(config: ModelCallConfig, private val random: Random) {
    @Inject
    constructor(settings: AgentSettings) : this(settings.models, Random.Default)

    private val retries = config.retries
    private val maxRetryAfter = config.maxRetryAfterSeconds.seconds

    /** What [call] for [turn] returns, made again after each [ModelException] that [delayBefore] retries. */
    suspend fun <T> retrying(turn: TurnId, call: suspend () -> T): T {
        var retry = 1
        while (true) {
            try {
                return call()
            } catch (e: ModelException) {
                val wait = delayBefore(retry, e.error) ?: throw e
                logger.warn(
                    "The model call of turn {} failed ({}): retry {} of {} in {}",
                    turn,
                    e.error.kind,
                    retry,
                    retries,
                    wait,
                )
                delay(wait)
                retry++
            }
        }
    }

    /** How long to wait before retry [retry] after [error], null when the call is not made again. */
    fun delayBefore(retry: Int, error: ModelError): Duration? {
        if (!error.retryable || error.outputStarted || retry > retries) return null
        val asked = error.retryAfter ?: return backoff(retry)
        return asked.takeIf { it <= maxRetryAfter }
    }

    private fun backoff(retry: Int): Duration =
        BACKOFF[minOf(retry, BACKOFF.size) - 1] * (1 - JITTER + 2 * JITTER * random.nextDouble())
}

private val BACKOFF = listOf(1.seconds, 4.seconds, 10.seconds)

private const val JITTER = 0.2

private val logger = LoggerFactory.getLogger(RetryPolicy::class.java)
