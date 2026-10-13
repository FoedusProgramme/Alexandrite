package org.foedusprogramme.alexandrite.agent.context

import org.foedusprogramme.alexandrite.agent.model.Endpoints
import org.foedusprogramme.alexandrite.agent.model.RetryPolicy
import org.foedusprogramme.alexandrite.agent.model.collectResponse
import org.foedusprogramme.alexandrite.agent.model.select
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.SummaryEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin

/** Has a model write the rolling summary of a conversation. */
@Singleton
internal class Summarizer(private val endpoints: Endpoints, private val retries: RetryPolicy) {
    /**
     * What [model] writes, in at most [maxTokens] tokens, as the summary of [previous] and [entries] that keeps [focus]
     * in view.
     */
    suspend fun summarize(
        model: ModelRef,
        ids: RequestIds,
        previous: SummaryEntry?,
        entries: List<TranscriptEntry>,
        focus: String?,
        maxTokens: Int,
    ): Written {
        val selected = endpoints.select(model) ?: throw ModelException(
            ModelError.builder(ModelErrorKind.MODEL_NOT_FOUND, "No endpoint lists the model $model.")
                .retryable(false)
                .build(),
        )
        val options = ModelOptions.builder()
            .maxOutputTokens(minOf(maxTokens, selected.info.maxOutputTokens ?: maxTokens))
            .build()
        val input = UserEntry(
            null,
            listOf(TextPart(summarizerInput(previous, entries))),
            UserOrigin.SystemWritten(SUMMARY_SOURCE),
        )
        val request = ModelRequest.builder(model, listOf(input), 0, ids)
            .instructions(summarizerSections(maxTokens, focus))
            .options(options)
            .build()
        val response = retries.retrying(ids.turn) { collectResponse(selected.endpoint, request) }
        val text = response.message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }.trim()
        return Written(text, response.usage)
    }

    /** The [text] that a summarizer wrote, blank when it wrote none, and what the call took. */
    class Written(val text: String, val usage: Usage)
}
