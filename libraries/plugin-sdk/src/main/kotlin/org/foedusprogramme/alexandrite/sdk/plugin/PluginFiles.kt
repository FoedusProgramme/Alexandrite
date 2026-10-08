package org.foedusprogramme.alexandrite.sdk.plugin

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal
import java.nio.file.Path

/** The plugin's own directories. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface PluginFiles {
    public val dataDir: Path

    /** A directory whose contents may be deleted at any time. */
    public val cacheDir: Path
}
