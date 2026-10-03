package org.foedusprogramme.alexandrite.provider.anthropic

import org.foedusprogramme.alexandrite.internal.InternalPlaceholder
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex

/** Placeholder that compiles against its allowed dependencies; replaced by the Anthropic provider in a later task. */
object AnthropicPlaceholder {
    val module: String = "provider-anthropic"
    val requires: List<String> = listOf(ModuleIndex::class.java.name, InternalPlaceholder.module)
}
