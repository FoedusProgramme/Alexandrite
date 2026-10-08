package org.foedusprogramme.alexandrite.sdk.model

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.tool.ToolNames

/** How a model samples and ends its output, where null leaves an option at the provider's default. */
@Poko
public class ModelOptions private constructor(
    public val maxOutputTokens: Int?,
    public val temperature: Double?,
    /** The probability mass of nucleus sampling. */
    public val topP: Double?,
    /** Texts that end the output where the model writes them. */
    public val stopSequences: List<String>,
    public val reasoning: ReasoningEffort?,
    /** Whether the model may call several tools in one response. */
    public val parallelToolCalls: Boolean?,
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "A maximum of output tokens is positive, was $maxOutputTokens."
        }
        require(temperature == null || (temperature.isFinite() && temperature >= 0)) {
            "A temperature is a finite number of at least 0, was $temperature."
        }
        require(topP == null || topP in 0.0..1.0) { "A top-p is between 0 and 1, was $topP." }
        require(stopSequences.none(String::isEmpty)) { "A stop sequence may not be empty." }
    }

    public fun toBuilder(): Builder = Builder()
        .maxOutputTokens(maxOutputTokens)
        .temperature(temperature)
        .topP(topP)
        .stopSequences(stopSequences)
        .reasoning(reasoning)
        .parallelToolCalls(parallelToolCalls)

    public class Builder internal constructor() {
        private var maxOutputTokens: Int? = null
        private var temperature: Double? = null
        private var topP: Double? = null
        private var stopSequences: List<String> = emptyList()
        private var reasoning: ReasoningEffort? = null
        private var parallelToolCalls: Boolean? = null

        public fun maxOutputTokens(maxOutputTokens: Int?): Builder = apply { this.maxOutputTokens = maxOutputTokens }

        public fun temperature(temperature: Double?): Builder = apply { this.temperature = temperature }

        public fun topP(topP: Double?): Builder = apply { this.topP = topP }

        public fun stopSequences(stopSequences: List<String>): Builder =
            apply { this.stopSequences = stopSequences.toList() }

        public fun reasoning(reasoning: ReasoningEffort?): Builder = apply { this.reasoning = reasoning }

        public fun parallelToolCalls(parallelToolCalls: Boolean?): Builder =
            apply { this.parallelToolCalls = parallelToolCalls }

        public fun build(): ModelOptions =
            ModelOptions(maxOutputTokens, temperature, topP, stopSequences, reasoning, parallelToolCalls)
    }

    public companion object {
        /** Every option at the provider's default. */
        public val DEFAULT: ModelOptions = Builder().build()

        public fun builder(): Builder = Builder()
    }
}

public inline fun ModelOptions.rebuild(block: ModelOptions.Builder.() -> Unit): ModelOptions =
    toBuilder().apply(block).build()

/** How much a model reasons before it answers. */
@JvmInline
@Serializable
public value class ReasoningEffort internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** Thinking off. */
        public val NONE: ReasoningEffort = ReasoningEffort("none")

        public val MINIMAL: ReasoningEffort = ReasoningEffort("minimal")

        public val LOW: ReasoningEffort = ReasoningEffort("low")

        public val MEDIUM: ReasoningEffort = ReasoningEffort("medium")

        public val HIGH: ReasoningEffort = ReasoningEffort("high")

        /** More than [HIGH]. */
        public val XHIGH: ReasoningEffort = ReasoningEffort("xhigh")

        /** The most the model offers. */
        public val MAX: ReasoningEffort = ReasoningEffort("max")

        /** The values this version knows. */
        public val entries: List<ReasoningEffort> = listOf(NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ReasoningEffort = ReasoningEffort(id)
    }
}

/** Whether the model must call a tool. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ToolChoice {
    /** The model decides. */
    @OptIn(InternalAlexandriteApi::class)
    public data object Auto : ToolChoice

    /** No tool call. */
    @OptIn(InternalAlexandriteApi::class)
    public data object None : ToolChoice

    /** At least one tool call. */
    @OptIn(InternalAlexandriteApi::class)
    public data object Required : ToolChoice

    /** A call of the tool [name]. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Named(public val name: String) : ToolChoice {
        init {
            require(ToolNames.PATTERN.matches(name)) { "Malformed tool name '$name'." }
        }
    }
}
