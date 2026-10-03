package org.foedusprogramme.alexandrite.tools

import org.foedusprogramme.alexandrite.internal.InternalPlaceholder
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex

/** Placeholder that compiles against its allowed dependencies; replaced by the built-in tools in a later task. */
object ToolsPlaceholder {
    val module: String = "tools"
    val requires: List<String> = listOf(ModuleIndex::class.java.name, InternalPlaceholder.module)
}
