package org.foedusprogramme.alexandrite.runtime.lifecycle

import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.hook.HookDispatcher
import org.foedusprogramme.alexandrite.runtime.hook.HookFailureListener
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

private const val RUNTIME_PLUGIN = "alexandrite-runtime"

/** What the runtime itself binds for [plugins]. */
internal fun runtimeBindings(
    config: RuntimeConfig,
    plugins: List<PluginInfo>,
    hookFailures: HookFailureListener,
    control: RuntimeControl,
): PluginBindings {
    val hooks = key<Hook>()
    val bindings = listOf(
        binding(
            key<Hooks>(),
            RUNTIME_PLUGIN,
            "Hooks",
            dependencies = listOf(Dependency(hooks, DependencyKind.ALL, "hooks")),
        ) { r -> HookDispatcher(r.getAll(hooks), hookFailures) },
        instanceBinding(key<Clock>(), Clock.system(config.zone), RUNTIME_PLUGIN, "Clock"),
        instanceBinding(key<RuntimeControl>(), control, RUNTIME_PLUGIN, "RuntimeControl"),
    ) + plugins.flatMap { plugin ->
        listOf(
            instanceBinding(key<PluginInfo>(plugin.id), plugin, RUNTIME_PLUGIN, "PluginInfo of ${plugin.id}"),
            instanceBinding(
                key<PluginFiles>(plugin.id),
                PluginDataDirectory(config.dataDir.resolve("plugins").resolve(plugin.id)),
                RUNTIME_PLUGIN,
                "PluginFiles of ${plugin.id}",
            ),
        )
    }
    return PluginBindings(RUNTIME_PLUGIN, bindings)
}

private class PluginDataDirectory(directory: Path) : PluginFiles {
    override val dataDir: Path by lazy { Files.createDirectories(directory) }
}
