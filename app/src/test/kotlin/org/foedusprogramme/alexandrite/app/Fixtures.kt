package org.foedusprogramme.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.RuntimeListener
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.config.JsonConfigSource
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import java.nio.file.Path

class TestIndex(id: String, private val bindings: List<Binding<*>> = emptyList()) : PluginIndex {
    override val info = PluginInfo(id, id, "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
    override val configRoot = PluginIds.thirdPartyRoot(id)

    override fun bindings() = bindings

    override fun configSections() = emptyList<ConfigSectionSpec<*>>()
}

class Failing(private val step: String) : Lifecycle {
    override suspend fun onStart() = check(step != "start") { "cannot start" }

    override suspend fun onOpen() = check(step != "open") { "cannot open" }

    override fun onStop() = check(step != "stop") { "cannot stop" }
}

fun failing(plugin: String, step: String): Binding<Failing> = binding(key(), plugin, "Failing") { Failing(step) }

fun spec(
    dataDir: Path,
    vararg indexes: PluginIndex,
    config: String = "{}",
    listener: RuntimeListener = RuntimeListener {},
): RuntimeSpec = RuntimeSpec.builder(RuntimeConfig.builder(dataDir).build(), PluginSet.of(*indexes))
    .pluginConfig(JsonConfigSource(Json.parseToJsonElement(config).jsonObject))
    .listener(listener)
    .build()
