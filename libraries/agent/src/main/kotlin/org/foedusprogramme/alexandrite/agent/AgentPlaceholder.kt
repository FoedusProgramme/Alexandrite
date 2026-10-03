package org.foedusprogramme.alexandrite.agent

import org.foedusprogramme.alexandrite.internal.InternalPlaceholder
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex

/** Placeholder that compiles against its allowed dependencies; replaced by the turn pipeline, permission, store and automation in a later task. */
object AgentPlaceholder {
    val module: String = "agent"
    val requires: List<String> = listOf(ModuleIndex::class.java.name, InternalPlaceholder.module)
}
