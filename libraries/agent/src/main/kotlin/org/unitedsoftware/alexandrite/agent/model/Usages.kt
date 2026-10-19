package org.unitedsoftware.alexandrite.agent.model

import org.unitedsoftware.alexandrite.sdk.model.Usage

/** The usage of this call and the [later] one together, where a count either leaves out is unknown. */
internal operator fun Usage.plus(later: Usage): Usage = Usage.builder()
    .inputTokens(sum(inputTokens, later.inputTokens))
    .cacheReadTokens(sum(cacheReadTokens, later.cacheReadTokens))
    .cacheWriteTokens(sum(cacheWriteTokens, later.cacheWriteTokens))
    .outputTokens(sum(outputTokens, later.outputTokens))
    .reasoningTokens(sum(reasoningTokens, later.reasoningTokens))
    .contextTokens(later.contextTokens)
    .requests(requests + later.requests)
    .build()

private fun sum(count: Long?, other: Long?): Long? = if (count == null || other == null) null else count + other
