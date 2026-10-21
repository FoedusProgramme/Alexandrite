package org.unitedsoftware.alexandrite.sdk.runtime

import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
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

    /** The host's config file, null when the host names none. */
    public val configFile: Path?

    /** The file [configFile] names with its links followed. */
    public val configTarget: Path?

    /** The copy of the config file that the host keeps beside [configTarget] when it writes the file. */
    public val configBackup: Path?

    /** The places the host declares off-limits. */
    public val protected: List<Path>

    /** Every place of Alexandrite's own: the config's directory and files, the data and cache, and [protected]. */
    public val alexandritePlaces: List<Path>
        get() = listOfNotNull(configFile?.parent, configTarget, configBackup) +
            listOf(dataRoot, pluginDataRoot, cacheRoot) + protected
}
