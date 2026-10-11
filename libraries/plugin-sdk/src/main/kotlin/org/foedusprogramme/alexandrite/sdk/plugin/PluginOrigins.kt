package org.foedusprogramme.alexandrite.sdk.plugin

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi

/** Which plugins created the instances of the runtime. */
@InternalAlexandriteApi
public interface PluginOrigins {
    /** Whether a plugin on the runtime's built-in list bound [instance]. */
    public fun isBuiltIn(instance: Any): Boolean
}
