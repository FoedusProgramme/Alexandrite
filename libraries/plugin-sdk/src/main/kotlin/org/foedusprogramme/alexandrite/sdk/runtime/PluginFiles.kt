package org.foedusprogramme.alexandrite.sdk.runtime

import org.foedusprogramme.alexandrite.sdk.di.PluginLocal
import java.nio.file.Path

/** The plugin's own data directory. */
@PluginLocal
public interface PluginFiles {
    public val dataDir: Path
}
