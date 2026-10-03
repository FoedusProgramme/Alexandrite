package org.foedusprogramme.alexandrite.provider.openaicompatible

import org.foedusprogramme.alexandrite.internal.InternalPlaceholder
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex

/** Placeholder that compiles against its allowed dependencies; replaced by the OpenAI-compatible provider in a later task. */
object OpenAiCompatPlaceholder {
    val module: String = "provider-openai-compatible"
    val requires: List<String> = listOf(ModuleIndex::class.java.name, InternalPlaceholder.module)
}
