package org.foedusprogramme.alexandrite.sdk.model

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.requireOpaqueId
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.Part
import org.foedusprogramme.alexandrite.sdk.transcript.ProviderData
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry

/** One request to a model. */
@Poko
public class ModelRequest private constructor(
    public val model: ModelRef,
    /** The system prompt, in order. */
    public val instructions: List<PromptSection>,
    /** The entries the model reads, oldest first, with their media inline. */
    public val history: List<TranscriptEntry>,
    /** The index in [history] of the current turn's first entry. */
    public val turnStart: Int,
    /** Text that the provider renders into this request alone. */
    public val turnContext: List<TurnContextItem>,
    public val tools: List<ToolDefinition>,
    public val toolChoice: ToolChoice,
    public val options: ModelOptions,
    public val ids: RequestIds,
    /** Keeps the requests that share it on one prompt cache, null for none. */
    public val cacheKey: String?,
    /** Options that only the provider of their wire dialect reads. */
    public val providerOptions: ProviderData,
) {
    init {
        require(turnStart in 0..history.size) {
            "Turn start $turnStart lies outside a history of ${history.size} entries."
        }
        requireDistinct(instructions.map { it.id }, "prompt section")
        requireDistinct(tools.map { it.name }, "tool")
        if (toolChoice is ToolChoice.Named) {
            require(tools.any { it.name == toolChoice.name }) {
                "The request forces tool '${toolChoice.name}', which it does not offer."
            }
        }
        require(toolChoice != ToolChoice.Required || tools.isNotEmpty()) {
            "The request requires a tool call but offers no tools."
        }
        require(cacheKey == null || cacheKey.isNotEmpty()) { "A cache key may not be empty." }
        val stored = history.indexOfFirst { entry -> entry.parts().any { it is MediaPart && it.source is StoredMedia } }
        require(stored < 0) { "Entry $stored of the history refers to stored media, which a request carries inline." }
    }

    public fun toBuilder(): Builder = Builder(model, history, turnStart, ids)
        .instructions(instructions)
        .turnContext(turnContext)
        .tools(tools)
        .toolChoice(toolChoice)
        .options(options)
        .cacheKey(cacheKey)
        .providerOptions(providerOptions)

    public class Builder internal constructor(
        private var model: ModelRef,
        history: List<TranscriptEntry>,
        private var turnStart: Int,
        private var ids: RequestIds,
    ) {
        private var history: List<TranscriptEntry> = history.toList()
        private var instructions: List<PromptSection> = emptyList()
        private var turnContext: List<TurnContextItem> = emptyList()
        private var tools: List<ToolDefinition> = emptyList()
        private var toolChoice: ToolChoice = ToolChoice.Auto
        private var options: ModelOptions = ModelOptions.DEFAULT
        private var cacheKey: String? = null
        private var providerOptions: ProviderData = ProviderData.EMPTY

        public fun model(model: ModelRef): Builder = apply { this.model = model }

        public fun instructions(instructions: List<PromptSection>): Builder =
            apply { this.instructions = instructions.toList() }

        public fun history(history: List<TranscriptEntry>): Builder = apply { this.history = history.toList() }

        public fun turnStart(turnStart: Int): Builder = apply { this.turnStart = turnStart }

        public fun turnContext(turnContext: List<TurnContextItem>): Builder =
            apply { this.turnContext = turnContext.toList() }

        public fun tools(tools: List<ToolDefinition>): Builder = apply { this.tools = tools.toList() }

        public fun toolChoice(toolChoice: ToolChoice): Builder = apply { this.toolChoice = toolChoice }

        public fun options(options: ModelOptions): Builder = apply { this.options = options }

        public fun ids(ids: RequestIds): Builder = apply { this.ids = ids }

        public fun cacheKey(cacheKey: String?): Builder = apply { this.cacheKey = cacheKey }

        public fun providerOptions(providerOptions: ProviderData): Builder =
            apply { this.providerOptions = providerOptions }

        public fun build(): ModelRequest = ModelRequest(
            model,
            instructions,
            history,
            turnStart,
            turnContext,
            tools,
            toolChoice,
            options,
            ids,
            cacheKey,
            providerOptions,
        )
    }

    public companion object {
        public fun builder(model: ModelRef, history: List<TranscriptEntry>, turnStart: Int, ids: RequestIds): Builder =
            Builder(model, history, turnStart, ids)
    }
}

public inline fun ModelRequest.rebuild(block: ModelRequest.Builder.() -> Unit): ModelRequest =
    toBuilder().apply(block).build()

/** A section of the system prompt. */
@Poko
public class PromptSection(
    public val id: String,
    public val text: String,
    /** Whether the text stays the same, byte for byte, from turn to turn. */
    public val stable: Boolean,
) {
    init {
        requireOpaqueId(id, "prompt section")
    }
}

/** The conversation, turn and round a request belongs to. */
@Poko
public class RequestIds(
    public val conversation: ConversationId,
    public val turn: TurnId,
    /** 0 for the turn's first request. */
    public val round: Int,
) {
    init {
        require(round >= 0) { "A round is at least 0, was $round." }
    }

    /** The id of the tool call at part [index] of this round's response, for a backend that gives the call none. */
    public fun callId(index: Int): ToolCallId {
        require(index >= 0) { "A part index is at least 0, was $index." }
        return ToolCallId("call-$turn-$round-$index")
    }
}

private fun requireDistinct(names: List<String>, what: String) {
    val repeated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(repeated.isEmpty()) { "The request names ${repeated.joinToString { "$what '$it'" }} more than once." }
}

private fun TranscriptEntry.parts(): List<Part> = when (this) {
    is UserEntry -> parts
    is ToolResultEntry -> content
    else -> emptyList()
}
