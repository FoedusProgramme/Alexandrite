package org.unitedsoftware.alexandrite.sdk.plugin

import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.di.PluginLocal
import java.nio.file.Path

/** The plugin's own directories. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface PluginFiles {
    public val dataDir: Path

    /** A directory whose contents may be deleted at any time. */
    public val cacheDir: Path
}
