package org.foedusprogramme.alexandrite.sdk.plugin

import kotlinx.coroutines.CoroutineScope
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal

/** The scope of the plugin's long-running coroutines. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface PluginScope : CoroutineScope
