package org.foedusprogramme.alexandrite.runtime.lifecycle

import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.chat.MemoryChatStateStore
import org.foedusprogramme.alexandrite.runtime.chat.PluginChatStates
import org.foedusprogramme.alexandrite.runtime.chat.UnreadableStateListener
import org.foedusprogramme.alexandrite.runtime.hook.HookDecisionListener
import org.foedusprogramme.alexandrite.runtime.hook.HookDispatcher
import org.foedusprogramme.alexandrite.runtime.hook.HookFailureListener
import org.foedusprogramme.alexandrite.runtime.turn.PluginTurnInitiator
import org.foedusprogramme.alexandrite.sdk.channel.ChannelControl
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
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
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiation
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiator
import java.nio.file.Path
import java.time.Clock
import kotlin.coroutines.CoroutineContext

internal const val RUNTIME_PLUGIN = "alexandrite-runtime"

internal const val PLUGINS_DIRECTORY = "plugins"

/**
 * What the runtime itself binds for [plugins], where [initiated] tells whether a plugin binds the [TurnInitiation]
 * that every plugin's [TurnInitiator] queues its turns with.
 */
internal fun runtimeBindings(
    config: RuntimeConfig,
    plugins: List<PluginInfo>,
    hookFailures: HookFailureListener,
    hookDecisions: HookDecisionListener,
    unreadableStates: UnreadableStateListener,
    control: (plugin: String) -> RuntimeControl,
    scopes: PluginScopes,
    directory: ChannelDirectory,
    channels: ChannelControl,
    initiated: Boolean,
    context: CoroutineContext,
): PluginBindings {
    val hooks = key<Hook>()
    val stateStore = key<ChatStateStore>()
    val initiation = key<TurnInitiation>()
    val memory by lazy { MemoryChatStateStore() }
    val bindings = listOf(
        binding(
            key<Hooks>(),
            RUNTIME_PLUGIN,
            "Hooks",
            dependencies = listOf(Dependency(hooks, DependencyKind.ALL, "hooks")),
        ) { r ->
            val dispatcher =
                HookDispatcher(r.getAll(hooks), hookFailures, asyncContext = context, decisions = hookDecisions)
            if (dispatcher.hasAsyncObservers) dispatcher else object : Hooks by dispatcher {}
        },
        instanceBinding(key<Clock>(), Clock.system(config.zone), RUNTIME_PLUGIN, "Clock"),
        instanceBinding(key<HostPaths>(), RuntimeHostPaths(config), RUNTIME_PLUGIN, "HostPaths"),
        instanceBinding(key<ChannelDirectory>(), directory, RUNTIME_PLUGIN, "ChannelDirectory"),
        instanceBinding(key<ChannelControl>(), channels, RUNTIME_PLUGIN, "ChannelControl"),
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
            binding(
                key<TurnInitiator>(plugin.id),
                RUNTIME_PLUGIN,
                "TurnInitiator of ${plugin.id}",
                dependencies = listOfNotNull(
                    Dependency(initiation, DependencyKind.LAZY, "initiation").takeIf { initiated },
                ),
                managed = false,
            ) { r -> PluginTurnInitiator(plugin.id, if (initiated) r.lazy(initiation) else null) },
        )
    }
    return PluginBindings(RUNTIME_PLUGIN, bindings)
}

private class PluginDirectories(private val data: Path, private val cache: Path) : PluginFiles {
    override val dataDir: Path get() = data.also(::createOwnerOnly)
    override val cacheDir: Path get() = cache.also(::createOwnerOnly)
}

private class RuntimeHostPaths(config: RuntimeConfig) : HostPaths {
    override val dataRoot: Path = config.dataDir.absolute()
    override val cacheRoot: Path = config.cacheDir.absolute()
    override val configFile: Path? = config.configFile?.absolute()
    override val protected: List<Path> = config.protected.map { it.absolute() }.distinct()
}

private fun Path.absolute(): Path = toAbsolutePath().normalize()
