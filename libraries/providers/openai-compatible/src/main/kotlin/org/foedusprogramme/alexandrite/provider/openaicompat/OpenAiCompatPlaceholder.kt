package org.foedusprogramme.alexandrite.provider.openaicompat

import org.foedusprogramme.alexandrite.common.CommonPlaceholder
import org.foedusprogramme.alexandrite.sdk.SdkPlaceholder

/** Placeholder that compiles against its allowed dependencies; replaced by the OpenAI-compatible provider in a later task. */
object OpenAiCompatPlaceholder {
    val module: String = "provider-openai-compatible"
    val requires: List<String> = listOf(SdkPlaceholder.module, CommonPlaceholder.module)
}
