package org.unitedsoftware.alexandrite.sdk.plugin

import kotlinx.coroutines.CoroutineScope
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.di.PluginLocal

/** The scope of the plugin's long-running coroutines. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface PluginScope : CoroutineScope
