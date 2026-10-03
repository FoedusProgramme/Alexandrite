package org.foedusprogramme.alexandrite.sdk.runtime

import org.foedusprogramme.alexandrite.sdk.di.ModuleLocal
import java.nio.file.Path

/** The module's own data directory. */
@ModuleLocal
public interface PluginFiles {
    public val dataDir: Path
}
