package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.json.JsonElement
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal

/**
 * Writes changes below the plugin's config root into the config file, leaving the rest of the file as it is.
 *
 * An owner that applies what it wrote holds a lock of its own from the edit to the apply, so that its changes take
 * effect in the order they reach the file.
 */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ConfigEditor {
    /** Writes all the changes [block] makes to the file as it is now, or none and throws [ConfigEditException]. */
    public suspend fun edit(block: ConfigEdit.() -> Unit)
}

public suspend fun ConfigEditor.set(path: String, value: JsonElement): Unit = edit { set(path, value) }

public suspend fun ConfigEditor.remove(path: String): Unit = edit { remove(path) }

public fun ConfigEdit.setLiteral(path: String, text: String): Unit = setLiteral(path, ConfigLiteral.Text(text))

/**
 * The changes of one [ConfigEditor.edit], applied in order. A path is the keys of nested objects below the plugin's
 * config root joined by dots, so a list is set as a whole.
 */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ConfigEdit {
    /** The value at [path] as the file writes it after this edit's changes so far, null when there is none. */
    public fun get(path: String): JsonElement?

    /**
     * The strings and lists at [path] as the config resolves them after this edit's changes so far, null when there is
     * none or the value holds anything else.
     */
    public fun getLiteral(path: String): ConfigLiteral?

    /** Writes [value] at [path] as an operator writes it, creating the objects on the way that are missing. */
    public fun set(path: String, value: JsonElement)

    /** Writes [value] at [path] so that the config reads it back, creating the objects on the way that are missing. */
    public fun setLiteral(path: String, value: ConfigLiteral)

    /** Takes out the member at [path] when there is one. */
    public fun remove(path: String)
}
