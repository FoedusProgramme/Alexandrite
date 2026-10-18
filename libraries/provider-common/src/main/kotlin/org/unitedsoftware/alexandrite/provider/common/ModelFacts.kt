package org.unitedsoftware.alexandrite.provider.common

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind

/** What one source says about a model, null where it says nothing. */
@Poko
public class ModelFacts(
    public val id: String?,
    public val displayName: String? = null,
    public val contextWindow: Int? = null,
    public val maxOutputTokens: Int? = null,
    public val nativeTools: Boolean? = null,
    public val parallelToolCalls: Boolean? = null,
    public val inputMedia: Set<MediaKind>? = null,
    public val reasoningEfforts: Set<ReasoningEffort>? = null,
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
