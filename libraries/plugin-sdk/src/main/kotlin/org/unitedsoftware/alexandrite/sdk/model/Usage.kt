package org.unitedsoftware.alexandrite.sdk.model

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonObject

/**
 * The tokens of one model call, null where the backend does not say.
 *
 * A count that contradicts the others, such as cached tokens beyond the input tokens, is left out when it is built.
 */
@Poko
public class Usage private constructor(
    /** Prompt tokens of every request, cached ones included. */
    public val inputTokens: Long?,
    public val cacheReadTokens: Long?,
    public val cacheWriteTokens: Long?,
    /** Tokens the model wrote, reasoning included. */
    public val outputTokens: Long?,
    public val reasoningTokens: Long?,
    /** Prompt tokens of the call's last request. */
    public val contextTokens: Long?,
    /** How many requests the call made. */
    public val requests: Int,
    /** The backend's own usage object. */
    public val raw: JsonObject?,
) {
    init {
        for ((name, count) in listOf(
            "input" to inputTokens,
            "cache read" to cacheReadTokens,
            "cache write" to cacheWriteTokens,
            "output" to outputTokens,
            "reasoning" to reasoningTokens,
            "context" to contextTokens,
        )) {
            require(count == null || count >= 0) { "A count of $name tokens is at least 0, was $count." }
        }
        require(requests >= 1) { "A call makes at least 1 request, was $requests." }
    }

    public fun toBuilder(): Builder = Builder()
        .inputTokens(inputTokens)
        .cacheReadTokens(cacheReadTokens)
        .cacheWriteTokens(cacheWriteTokens)
        .outputTokens(outputTokens)
        .reasoningTokens(reasoningTokens)
        .contextTokens(contextTokens)
        .requests(requests)
        .raw(raw)

    public class Builder internal constructor() {
        private var inputTokens: Long? = null
        private var cacheReadTokens: Long? = null
        private var cacheWriteTokens: Long? = null
        private var outputTokens: Long? = null
        private var reasoningTokens: Long? = null
        private var contextTokens: Long? = null
        private var requests: Int = 1
        private var raw: JsonObject? = null

        public fun inputTokens(inputTokens: Long?): Builder = apply { this.inputTokens = inputTokens }

        public fun cacheReadTokens(cacheReadTokens: Long?): Builder = apply { this.cacheReadTokens = cacheReadTokens }

        public fun cacheWriteTokens(cacheWriteTokens: Long?): Builder =
            apply { this.cacheWriteTokens = cacheWriteTokens }

        public fun outputTokens(outputTokens: Long?): Builder = apply { this.outputTokens = outputTokens }

        public fun reasoningTokens(reasoningTokens: Long?): Builder = apply { this.reasoningTokens = reasoningTokens }

        public fun contextTokens(contextTokens: Long?): Builder = apply { this.contextTokens = contextTokens }

        public fun requests(requests: Int): Builder = apply { this.requests = requests }

        public fun raw(raw: JsonObject?): Builder = apply { this.raw = raw }

        public fun build(): Usage {
            val input = inputTokens
            var cacheRead = cacheReadTokens
            var cacheWrite = cacheWriteTokens
            if (input != null && (cacheRead ?: 0) + (cacheWrite ?: 0) > input) {
                cacheWrite = null
                if ((cacheRead ?: 0) > input) cacheRead = null
            }
            val context = contextTokens?.takeUnless { input != null && it > input }
            val output = outputTokens
            val reasoning = reasoningTokens?.takeUnless { output != null && it > output }
            return Usage(input, cacheRead, cacheWrite, output, reasoning, context, requests, raw)
        }
    }

    public companion object {
        public fun builder(): Builder = Builder()
    }
}

public inline fun Usage.rebuild(block: Usage.Builder.() -> Unit): Usage = toBuilder().apply(block).build()
