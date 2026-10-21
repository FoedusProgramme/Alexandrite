package org.unitedsoftware.alexandrite.sdk.config

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi

/** The host's whole config file as an operator changes it, with [ConfigPath] paths from the file's root. */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface HostConfig {
    /**
     * The value at [path] as the file writes it, the whole file for "", null when there is none, with each secret that
     * the file holds as a value in place of a `${'$'}{env:NAME}` reference shown as [HIDDEN_SECRET].
     */
    public suspend fun get(path: String): JsonElement?

    /**
     * Writes all the changes [block] makes to the file as it is now once the result passes the checks of a start, or
     * none and throws [ConfigEditException].
     */
    public suspend fun edit(block: ConfigEdit.() -> Unit): ConfigChanges

    /** Writes [text] in the place of the whole file, or nothing and throws [ConfigEditException]. */
    public suspend fun replace(text: String): ConfigChanges

    /**
     * What [edit] would write for [block] without writing it, or throws [ConfigEditException] as [edit] would; null
     * when this host cannot tell.
     */
    public suspend fun check(block: ConfigEdit.() -> Unit): ConfigChanges? = null

    public companion object {
        /** What [get] shows in the place of a secret's value. */
        public const val HIDDEN_SECRET: String = "[secret]"
    }
}

/** The changes of one [HostConfig.edit], applied in order, at paths as [ConfigPath] writes them. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ConfigEdit {
    /** The value at [path] as the file writes it after this edit's changes so far, null when there is none. */
    public fun get(path: String): JsonElement?

    /** Writes [value] at [path] as an operator writes it, creating the objects on the way that are missing. */
    public fun set(path: String, value: JsonElement)

    /** Takes out the member at [path] when there is one. */
    public fun remove(path: String)
}

/** What a [HostConfig] change wrote. */
@Poko
public class ConfigChanges private constructor(
    /** The paths whose values changed, in file order. */
    public val changed: List<String>,
    /** The paths of [changed] that take effect only once the runtime starts again, the others having taken effect. */
    public val restart: List<String>,
) {
    public companion object {
        public operator fun invoke(changed: List<String>, restart: List<String>): ConfigChanges =
            ConfigChanges(changed.toList(), restart.toList())
    }
}
