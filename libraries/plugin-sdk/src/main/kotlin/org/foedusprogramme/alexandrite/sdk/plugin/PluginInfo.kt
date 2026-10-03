package org.foedusprogramme.alexandrite.sdk.plugin

import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal
import java.util.Objects

/** What a plugin says about itself. */
@PluginLocal
public class PluginInfo(
    public val id: String,
    public val name: String,
    public val version: String,
    public val description: String,
    /** The [AlexandriteSdk.API_VERSION] the plugin was compiled against. */
    public val sdkApi: Int,
    /** Ids of the plugins this one needs. */
    public val requires: List<String>,
    /** Fully qualified name of the plugin's [Plugin] class. */
    public val entryClass: String,
) {
    override fun equals(other: Any?): Boolean = other is PluginInfo &&
        id == other.id &&
        name == other.name &&
        version == other.version &&
        description == other.description &&
        sdkApi == other.sdkApi &&
        requires == other.requires &&
        entryClass == other.entryClass

    override fun hashCode(): Int = Objects.hash(id, name, version, description, sdkApi, requires, entryClass)

    override fun toString(): String = "PluginInfo(id=$id, name=$name, version=$version, description=$description, " +
        "sdkApi=$sdkApi, requires=$requires, entryClass=$entryClass)"
}
