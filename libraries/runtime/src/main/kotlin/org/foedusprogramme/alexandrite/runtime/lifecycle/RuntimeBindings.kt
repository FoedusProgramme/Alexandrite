package org.foedusprogramme.alexandrite.runtime.lifecycle

import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.chat.MemoryChatStateStore
import org.foedusprogramme.alexandrite.runtime.chat.PluginChatStates
import org.foedusprogramme.alexandrite.runtime.chat.UnreadableStateListener
import org.foedusprogramme.alexandrite.runtime.hook.HookDispatcher
import org.foedusprogramme.alexandrite.runtime.hook.HookFailureListener
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.chat.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.chat.ChatStates
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
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import java.nio.file.Path
import java.time.Clock
import kotlin.coroutines.CoroutineContext

internal const val RUNTIME_PLUGIN = "alexandrite-runtime"

internal const val PLUGINS_DIRECTORY = "plugins"

/** What the runtime itself binds for [plugins]. */
internal fun runtimeBindings(
    config: RuntimeConfig,
    plugins: List<PluginInfo>,
    hookFailures: HookFailureListener,
    unreadableStates: UnreadableStateListener,
    control: (plugin: String) -> RuntimeControl,
    scopes: PluginScopes,
    directory: ChannelDirectory,
    context: CoroutineContext,
): PluginBindings {
    val hooks = key<Hook>()
    val stateStore = key<ChatStateStore>()
    val memory by lazy { MemoryChatStateStore() }
    val bindings = listOf(
        binding(
            key<Hooks>(),
            RUNTIME_PLUGIN,
            "Hooks",
            dependencies = listOf(Dependency(hooks, DependencyKind.ALL, "hooks")),
        ) { r ->
            val dispatcher = HookDispatcher(r.getAll(hooks), hookFailures, asyncContext = context)
            if (dispatcher.hasAsyncObservers) dispatcher else object : Hooks by dispatcher {}
        },
        instanceBinding(key<Clock>(), Clock.system(config.zone), RUNTIME_PLUGIN, "Clock"),
        instanceBinding(key<ChannelDirectory>(), directory, RUNTIME_PLUGIN, "ChannelDirectory"),
    ) + plugins.flatMap { plugin ->
        listOf(
            instanceBinding(key<PluginInfo>(plugin.id), plugin, RUNTIME_PLUGIN, "PluginInfo of ${plugin.id}"),
            instanceBinding(
                key<PluginFiles>(plugin.id),
                PluginDirectories(
                    config.dataDir.resolve(PLUGINS_DIRECTORY).resolve(plugin.id),
                    config.cacheDir.resolve(PLUGINS_DIRECTORY).resolve(plugin.id),
                ),
                RUNTIME_PLUGIN,
                "PluginFiles of ${plugin.id}",
            ),
            instanceBinding(
                key<PluginScope>(plugin.id),
                scopes.create(plugin.id),
                RUNTIME_PLUGIN,
                "PluginScope of ${plugin.id}",
            ),
            instanceBinding(
                key<RuntimeControl>(plugin.id),
                control(plugin.id),
                RUNTIME_PLUGIN,
                "RuntimeControl of ${plugin.id}",
            ),
            binding(
                key<ChatStates>(plugin.id),
                RUNTIME_PLUGIN,
                "ChatStates of ${plugin.id}",
                dependencies = listOf(Dependency(stateStore, DependencyKind.OPTIONAL, "store")),
                managed = false,
            ) { r -> PluginChatStates(plugin.id, r.getOrNull(stateStore) ?: memory, unreadableStates) },
        )
    }
    return PluginBindings(RUNTIME_PLUGIN, bindings)
}

private class PluginDirectories(private val data: Path, private val cache: Path) : PluginFiles {
    override val dataDir: Path get() = data.also(::createOwnerOnly)
    override val cacheDir: Path get() = cache.also(::createOwnerOnly)
}
