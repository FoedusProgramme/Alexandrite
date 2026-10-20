package org.unitedsoftware.alexandrite.sdk.plugin

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.di.PluginLocal

/** What a plugin says about itself. */
@PluginLocal
@Poko
public class PluginInfo @InternalAlexandriteApi constructor(
    public val id: String,
    public val name: String,
    public val version: String,
    public val description: String,
    /** The [AlexandriteSdk.API_VERSION] the plugin was compiled against. */
    public val sdkApi: Int,
    /** Ids of the plugins this one needs. */
    public val requires: List<String>,
    /** Fully qualified name of the plugin's [Plugin] class. */
    public val entryClass: String,
)
