package org.unitedsoftware.alexandrite.agent.model

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.config.ModelCallConfig
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Calls a model again after a failure that may pass, as long as the failed call showed nothing but void output. */
@Singleton
internal class RetryPolicy(config: ModelCallConfig, private val random: Random) {
    @Inject
    constructor(settings: AgentSettings) : this(settings.models, Random.Default)

    private val retries = config.retries
    private val maxRetryAfter = config.maxRetryAfterSeconds.seconds

    /**
     * What [call] for [turn] returns, made again after each [ModelException] that [delayBefore] retries for a request
     * of about [promptTokens] prompt tokens.
     */
    suspend fun <T> retrying(turn: TurnId, promptTokens: Long = 0, call: suspend () -> T): T {
        var retry = 1
        while (true) {
            try {
                return call()
            } catch (e: ModelException) {
                val wait = delayBefore(retry, e.error, promptTokens) ?: throw e
                val once = onceBecause(e.error, promptTokens)
                logger.warn(
                    "The model call of turn {} failed ({}): retry {} of {} in {}{}",
                    turn,
                    e.error.kind,
                    retry,
                    if (once == null) retries else 1,
                    wait,
                    once?.let { ", the only one as $it" }.orEmpty(),
                )
                delay(wait)
                retry++
            }
        }
    }

    /**
     * How long to wait before retry [retry] after [error] of a request of about [promptTokens] prompt tokens, null when
     * the call is not made again.
     */
    fun delayBefore(retry: Int, error: ModelError, promptTokens: Long = 0): Duration? {
        if (!error.retryable || retry > retries) return null
        if (retry > 1 && onceBecause(error, promptTokens) != null) return null
        if (error.kind == ModelErrorKind.MALFORMED_OUTPUT) return Duration.ZERO
        if (error.outputStarted) return null
        val asked = error.retryAfter ?: return backoff(retry)
        return asked.takeIf { it <= maxRetryAfter }
    }

    /** Why a call that failed with [error] is retried once at most, null when the configured retries apply. */
    private fun onceBecause(error: ModelError, promptTokens: Long): String? = when {
        error.outputStarted -> "its output had started"
        promptTokens >= LARGE_PROMPT_TOKENS -> "its prompt of about $promptTokens tokens is large"
        else -> null
    }

    private fun backoff(retry: Int): Duration =
        BACKOFF[minOf(retry, BACKOFF.size) - 1] * (1 - JITTER + 2 * JITTER * random.nextDouble())
}

/** The estimated prompt tokens from which a failed request is sent again once at most. */
internal const val LARGE_PROMPT_TOKENS: Long = 100_000

private val BACKOFF = listOf(1.seconds, 4.seconds, 10.seconds)

private const val JITTER = 0.2

private val logger = LoggerFactory.getLogger(RetryPolicy::class.java)
