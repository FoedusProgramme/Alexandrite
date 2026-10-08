package org.foedusprogramme.alexandrite.sdk.model

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.chat.requireOpaqueId
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind

/** What a model of an endpoint can do, where a new builder claims nothing beyond text. */
@Poko
public class ModelInfo private constructor(
    /** The model's id at its endpoint. */
    public val id: String,
    /** The wire dialect of requests to the model. */
    public val dialect: Dialect,
    /** Null when the backend gives none. */
    public val displayName: String?,
    /** Tokens of prompt and output together, null when unknown. */
    public val contextWindow: Int?,
    /** Null when unknown. */
    public val maxOutputTokens: Int?,
    /** The kinds of media a request may carry inline. */
    public val inputMedia: Set<MediaKind>,
    /** Whether the model takes tool definitions and calls tools. */
    public val nativeTools: Boolean,
    /** Whether the model may call several tools in one response. */
    public val parallelToolCalls: Boolean,
    /** The efforts the model takes, empty when it takes no reasoning option. */
    public val reasoningEfforts: Set<ReasoningEffort>,
    /** Whether the endpoint streams deltas of the model's responses. */
    public val streaming: Boolean,
) {
    init {
        requireOpaqueId(id, "model")
        require(contextWindow == null || contextWindow > 0) { "A context window is positive, was $contextWindow." }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "A maximum of output tokens is positive, was $maxOutputTokens."
        }
        require(nativeTools || !parallelToolCalls) { "Model '$id' calls tools in parallel but takes no tools." }
    }

    public fun toBuilder(): Builder = Builder(id, dialect)
        .displayName(displayName)
        .contextWindow(contextWindow)
        .maxOutputTokens(maxOutputTokens)
        .inputMedia(inputMedia)
        .nativeTools(nativeTools)
        .parallelToolCalls(parallelToolCalls)
        .reasoningEfforts(reasoningEfforts)
        .streaming(streaming)

    public class Builder internal constructor(private var id: String, private var dialect: Dialect) {
        private var displayName: String? = null
        private var contextWindow: Int? = null
        private var maxOutputTokens: Int? = null
        private var inputMedia: Set<MediaKind> = emptySet()
        private var nativeTools: Boolean = false
        private var parallelToolCalls: Boolean = false
        private var reasoningEfforts: Set<ReasoningEffort> = emptySet()
        private var streaming: Boolean = false

        public fun id(id: String): Builder = apply { this.id = id }

        public fun dialect(dialect: Dialect): Builder = apply { this.dialect = dialect }

        public fun displayName(displayName: String?): Builder = apply { this.displayName = displayName }

        public fun contextWindow(contextWindow: Int?): Builder = apply { this.contextWindow = contextWindow }

        public fun maxOutputTokens(maxOutputTokens: Int?): Builder = apply { this.maxOutputTokens = maxOutputTokens }

        public fun inputMedia(inputMedia: Set<MediaKind>): Builder = apply { this.inputMedia = inputMedia.toSet() }

        public fun nativeTools(nativeTools: Boolean): Builder = apply { this.nativeTools = nativeTools }

        public fun parallelToolCalls(parallelToolCalls: Boolean): Builder =
            apply { this.parallelToolCalls = parallelToolCalls }

        public fun reasoningEfforts(reasoningEfforts: Set<ReasoningEffort>): Builder =
            apply { this.reasoningEfforts = reasoningEfforts.toSet() }

        public fun streaming(streaming: Boolean): Builder = apply { this.streaming = streaming }

        public fun build(): ModelInfo = ModelInfo(
            id,
            dialect,
            displayName,
            contextWindow,
            maxOutputTokens,
            inputMedia,
            nativeTools,
            parallelToolCalls,
            reasoningEfforts,
            streaming,
        )
    }

    public companion object {
        public fun builder(id: String, dialect: Dialect): Builder = Builder(id, dialect)
    }
}

public inline fun ModelInfo.rebuild(block: ModelInfo.Builder.() -> Unit): ModelInfo = toBuilder().apply(block).build()
