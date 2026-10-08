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
internal const val KEY_FUNCTION = "$DI.key"

internal const val CONTAINER = "$DI.container"
internal const val BINDING = "$CONTAINER.Binding"
internal const val DEPENDENCY = "$CONTAINER.Dependency"
internal const val DEPENDENCY_KIND = "$CONTAINER.DependencyKind"
internal const val SCOPE = "$CONTAINER.Scope"
internal const val BINDING_FUNCTION = "$CONTAINER.binding"

internal const val PLUGIN = "org.foedusprogramme.alexandrite.sdk.plugin.Plugin"
internal const val HOOK = "org.foedusprogramme.alexandrite.sdk.hook.Hook"
internal const val PLUGIN_INDEX = "org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex"
internal const val PLUGIN_INFO = "org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo"
internal const val ALEXANDRITE_SDK = "org.foedusprogramme.alexandrite.sdk.AlexandriteSdk"
internal const val INTERNAL_API = "org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi"

internal const val CHANNEL = "org.foedusprogramme.alexandrite.sdk.channel.Channel"
internal const val CHANNEL_INSTANCE = "org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance"

internal const val CONFIG_SECTION = "org.foedusprogramme.alexandrite.sdk.config.ConfigSection"
internal const val CONFIG_SECTION_SPEC = "org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec"
internal const val SERIALIZABLE = "kotlinx.serialization.Serializable"

internal const val LIST = "kotlin.collections.List"
internal const val LIST_OF = "kotlin.collections.listOf"
internal const val EMPTY_LIST = "kotlin.collections.emptyList"
internal const val LAZY = "kotlin.Lazy"
internal const val UNIT = "kotlin.Unit"
internal const val STRING = "kotlin.String"
internal const val OPT_IN = "kotlin.OptIn"
internal const val REQUIRES_OPT_IN = "kotlin.RequiresOptIn"
internal const val SUPPRESS = "kotlin.Suppress"

internal val COMPONENT_ANNOTATIONS = listOf(PLUGIN, SINGLETON, CHANNEL_INSTANCE_SCOPED, BINDS, CONTRIBUTE)

internal val INDEXED_CLASS_ANNOTATIONS = COMPONENT_ANNOTATIONS + CONFIG_SECTION

internal val FUNCTION_BINDING_ANNOTATIONS = listOf(SINGLETON, CHANNEL_INSTANCE_SCOPED, BINDS, CONTRIBUTE, NAMED)

/** The SDK API version the descriptor records. */
internal const val SDK_API_VERSION = 1

internal val PLUGIN_ID = Regex("[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*")
internal const val RESERVED_PREFIX = "alexandrite-"
internal const val THIRD_PARTY_ROOT = "plugins"

/** The config key that switches a plugin on and off. */
internal const val ENABLED = "enabled"

/** The config key that holds the config of a channel plugin's channel instances. */
internal const val INSTANCES = "instances"

/** A dot-separated config path below the config root. */
internal val CONFIG_PATH = Regex("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*")

internal const val SERVICE_FILE = "META-INF/services/$PLUGIN_INDEX"
internal const val DESCRIPTOR_DIRECTORY = "META-INF/alexandrite"
internal const val DESCRIPTOR_EXTENSION = "json"
