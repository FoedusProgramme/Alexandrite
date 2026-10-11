package org.foedusprogramme.alexandrite.sdk.runtime

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import java.nio.file.Path

/** The host's own places on disk, as absolute paths. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface HostPaths {
    /** The runtime's data directory. */
    public val dataRoot: Path

    /** The root of every plugin's data directory. */
    public val pluginDataRoot: Path

    /** The root of every plugin's cache directory. */
    public val cacheRoot: Path

    /** The config file the host read, null when it read none. */
    public val configFile: Path?

    /** The file [configFile] names with its links followed. */
    public val configTarget: Path? get() = configFile

    /** The copy of the config file that the host keeps when it writes the file. */
    public val configBackup: Path? get() = null

    /** The places the host declares off-limits. */
    public val protected: List<Path>
}
