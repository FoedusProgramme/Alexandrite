package org.foedusprogramme.alexandrite.ksp

internal const val MODULE_OPTION = "alexandrite.module"
internal const val CONFIG_ROOT_OPTION = "alexandrite.configRoot"

internal const val GENERATED_PACKAGE = "org.foedusprogramme.alexandrite.generated"

internal const val DI = "org.foedusprogramme.alexandrite.sdk.di"
internal const val SINGLETON = "$DI.Singleton"
internal const val CHANNEL_SCOPED = "$DI.ChannelScoped"
internal const val INJECT = "$DI.Inject"
internal const val NAMED = "$DI.Named"
internal const val BINDS = "$DI.Binds"
internal const val CONTRIBUTE = "$DI.Contribute"
internal const val MODULE_INDEX = "$DI.ModuleIndex"

internal const val CONFIG_SECTION = "org.foedusprogramme.alexandrite.sdk.config.ConfigSection"
internal const val CONFIG_SOURCE = "org.foedusprogramme.alexandrite.sdk.config.ConfigSource"
internal const val SERIALIZABLE = "kotlinx.serialization.Serializable"

internal val COMPONENT_ANNOTATIONS = listOf(SINGLETON, CHANNEL_SCOPED, BINDS, CONTRIBUTE)
