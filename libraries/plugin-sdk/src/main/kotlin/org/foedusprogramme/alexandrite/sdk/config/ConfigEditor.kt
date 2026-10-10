package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.json.JsonElement
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal

/** Writes changes below the plugin's config root into the config file, leaving the rest of the file as it is. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ConfigEditor {
    /** Writes all the changes [block] makes to the file as it is now, or none and throws [ConfigEditException]. */
    public suspend fun edit(block: ConfigEdit.() -> Unit)
}

public suspend fun ConfigEditor.set(path: String, value: JsonElement): Unit = edit { set(path, value) }

public suspend fun ConfigEditor.remove(path: String): Unit = edit { remove(path) }

/**
 * The changes of one [ConfigEditor.edit], applied in order. A path is the keys of nested objects below the plugin's
 * config root joined by dots, so a list is set as a whole.
 */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ConfigEdit {
    /** The value at [path] as the file writes it after this edit's changes so far, null when there is none. */
    public fun get(path: String): JsonElement?

    /** Writes [value] at [path] as an operator writes it, creating the objects on the way that are missing. */
    public fun set(path: String, value: JsonElement)

    /** Takes out the member at [path] when there is one. */
    public fun remove(path: String)
}
