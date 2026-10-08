package org.foedusprogramme.alexandrite.sdk.runtime

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal

/** Lets a plugin stop the runtime it runs in. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface RuntimeControl {
    /** Requests a stop in the plugin's name and returns at once. */
    public fun stop(request: StopRequest)
}
