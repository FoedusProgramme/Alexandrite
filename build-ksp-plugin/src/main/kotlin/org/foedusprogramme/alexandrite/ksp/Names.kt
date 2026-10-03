package org.foedusprogramme.alexandrite.ksp

internal const val DI = "org.foedusprogramme.alexandrite.sdk.di"
internal const val SINGLETON = "$DI.Singleton"
internal const val CHANNEL_INSTANCE_SCOPED = "$DI.ChannelInstanceScoped"
internal const val INJECT = "$DI.Inject"
internal const val NAMED = "$DI.Named"
internal const val BINDS = "$DI.Binds"
internal const val CONTRIBUTE = "$DI.Contribute"
internal const val PROVIDES = "$DI.Provides"
internal const val CONTRIBUTED_SPI = "$DI.ContributedSpi"
internal const val PLUGIN_LOCAL = "$DI.PluginLocal"

internal const val PLUGIN = "org.foedusprogramme.alexandrite.sdk.plugin.Plugin"
internal const val PLUGIN_INDEX = "org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex"
internal const val PLUGIN_INFO = "org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo"
internal const val ALEXANDRITE_SDK = "org.foedusprogramme.alexandrite.sdk.AlexandriteSdk"

internal const val CONFIG_SECTION = "org.foedusprogramme.alexandrite.sdk.config.ConfigSection"
internal const val CONFIG_SECTION_SPEC = "org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec"
internal const val SERIALIZABLE = "kotlinx.serialization.Serializable"

internal const val LIST = "kotlin.collections.List"
internal const val LAZY = "kotlin.Lazy"
internal const val UNIT = "kotlin.Unit"

internal val COMPONENT_ANNOTATIONS = listOf(PLUGIN, SINGLETON, CHANNEL_INSTANCE_SCOPED, BINDS, CONTRIBUTE)

internal val INDEXED_CLASS_ANNOTATIONS = COMPONENT_ANNOTATIONS + CONFIG_SECTION

internal val FUNCTION_BINDING_ANNOTATIONS = listOf(SINGLETON, CHANNEL_INSTANCE_SCOPED, BINDS, CONTRIBUTE, NAMED)
