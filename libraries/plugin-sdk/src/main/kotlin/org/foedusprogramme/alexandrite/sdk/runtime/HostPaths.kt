package org.foedusprogramme.alexandrite.sdk.runtime

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import java.nio.file.Path

/** The host's own places on disk, as absolute paths. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface HostPaths {
    /** The runtime's data directory. */
    public val dataRoot: Path

    /** The root of every plugin's cache directory. */
    public val cacheRoot: Path

    /** The config file the host read, null when it read none. */
    public val configFile: Path?

    /** The places the host declares off-limits. */
    public val protected: List<Path>
}
