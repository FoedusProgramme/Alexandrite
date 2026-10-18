package org.unitedsoftware.alexandrite.sdk.config

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi

/** The host's whole config file as an operator changes it, with paths from the file's root. */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface HostConfig {
    /** The value at [path] as the file writes it, null when there is none. */
    public suspend fun get(path: String): JsonElement?

    /**
     * Writes all the changes [block] makes to the file as it is now once the result passes the checks of a start, or
     * none and throws [ConfigEditException].
     */
    public suspend fun edit(block: ConfigEdit.() -> Unit): ConfigChanges

    /** Writes [text] in the place of the whole file, or nothing and throws [ConfigEditException]. */
    public suspend fun replace(text: String): ConfigChanges
}

/** What a [HostConfig] change wrote. */
@Poko
public class ConfigChanges(
    /** The paths whose values changed, in file order. */
    public val changed: List<String>,
    /** The paths of [changed] that take effect only once the runtime starts again. */
    public val restart: List<String>,
)
