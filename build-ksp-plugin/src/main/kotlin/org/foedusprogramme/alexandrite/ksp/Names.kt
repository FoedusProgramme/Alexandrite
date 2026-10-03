package org.foedusprogramme.alexandrite.ksp

internal const val MODULE_OPTION = "alexandrite.module"
internal const val CONFIG_ROOT_OPTION = "alexandrite.configRoot"
internal const val PACKAGE_OPTION = "alexandrite.package"
internal const val BUILT_IN_OPTION = "alexandrite.builtIn"

internal const val RESERVED_PREFIX = "alexandrite-"
internal const val THIRD_PARTY_ROOT = "plugins"
internal const val ENABLED = "enabled"

internal const val DI = "org.foedusprogramme.alexandrite.sdk.di"
internal const val SINGLETON = "$DI.Singleton"
internal const val CHANNEL_INSTANCE_SCOPED = "$DI.ChannelInstanceScoped"
internal const val INJECT = "$DI.Inject"
internal const val NAMED = "$DI.Named"
internal const val BINDS = "$DI.Binds"
internal const val CONTRIBUTE = "$DI.Contribute"
internal const val PROVIDES = "$DI.Provides"
internal const val CONTRIBUTED_SPI = "$DI.ContributedSpi"
internal const val MODULE_LOCAL = "$DI.ModuleLocal"
internal const val MODULE_INDEX = "$DI.ModuleIndex"

internal const val CONFIG_SECTION = "org.foedusprogramme.alexandrite.sdk.config.ConfigSection"
internal const val CONFIG_SECTION_SPEC = "org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec"
internal const val SERIALIZABLE = "kotlinx.serialization.Serializable"

internal val COMPONENT_ANNOTATIONS = listOf(SINGLETON, CHANNEL_INSTANCE_SCOPED, BINDS, CONTRIBUTE)

internal val PROVIDER_ANNOTATIONS = COMPONENT_ANNOTATIONS + NAMED
