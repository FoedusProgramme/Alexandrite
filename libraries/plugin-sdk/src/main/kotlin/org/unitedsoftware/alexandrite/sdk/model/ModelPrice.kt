package org.unitedsoftware.alexandrite.sdk.model

import dev.drewhamilton.poko.Poko
import java.math.BigDecimal
import java.math.RoundingMode

/** What a model's tokens cost, in US dollars per million tokens. */
@Poko
public class ModelPrice(
    public val input: BigDecimal,
    public val output: BigDecimal,
    /** The price of prompt tokens read from the cache, null for the [input] price. */
    public val cachedInput: BigDecimal? = null,
) {
    init {
        for ((name, price) in listOf("input" to input, "output" to output, "cached input" to cachedInput)) {
            require(price == null || price.signum() >= 0) { "A price of $name tokens is at least 0, was $price." }
        }
    }

    /** What the tokens of [usage] cost, null when it counts neither input nor output tokens. */
    public fun cost(usage: Usage): BigDecimal? {
        if (usage.inputTokens == null && usage.outputTokens == null) return null
        val input = usage.inputTokens ?: 0
        val cached = (usage.cacheReadTokens ?: 0).coerceAtMost(input)
        val total = input.minus(cached).toBigDecimal() * this.input +
            cached.toBigDecimal() * (cachedInput ?: this.input) +
            (usage.outputTokens ?: 0).toBigDecimal() * output
        return total.divide(MILLION, SCALE, RoundingMode.HALF_UP).stripTrailingZeros()
    }

    private companion object {
        val MILLION: BigDecimal = BigDecimal.valueOf(1_000_000)
        const val SCALE = 12
    }
}
