package org.foedusprogramme.alexandrite.provider.openaicompatible

import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind

/** What one source says about a model, null where it says nothing. */
internal data class ModelFacts(
    val id: String?,
    val displayName: String? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val nativeTools: Boolean? = null,
    val parallelToolCalls: Boolean? = null,
    val inputMedia: Set<MediaKind>? = null,
    val reasoningEfforts: Set<ReasoningEffort>? = null,
)

/** The model [id] as [configured], [listed] and [defaults] describe it, in that order, [listed] token limits first. */
internal fun modelInfo(
    id: String,
    dialect: Dialect,
    configured: ModelFacts?,
    listed: ModelFacts?,
    defaults: ModelFacts,
): ModelInfo {
    val sources = listOfNotNull(configured, listed, defaults)
    fun <T : Any> first(field: (ModelFacts) -> T?): T? = sources.firstNotNullOfOrNull(field)
    val nativeTools = first { it.nativeTools } ?: false
    return ModelInfo.builder(id, dialect)
        .displayName(first { it.displayName })
        .contextWindow(listed?.contextWindow ?: configured?.contextWindow)
        .maxOutputTokens(listed?.maxOutputTokens ?: configured?.maxOutputTokens)
        .nativeTools(nativeTools)
        .parallelToolCalls(nativeTools && first { it.parallelToolCalls } == true)
        .inputMedia(first { it.inputMedia }.orEmpty())
        .reasoningEfforts(first { it.reasoningEfforts }.orEmpty())
        .streaming(true)
        .build()
}
