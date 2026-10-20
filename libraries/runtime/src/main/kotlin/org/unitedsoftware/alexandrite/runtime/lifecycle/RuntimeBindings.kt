package org.unitedsoftware.alexandrite.runtime.lifecycle

import org.unitedsoftware.alexandrite.internal.path.createOwnerOnly
import org.unitedsoftware.alexandrite.runtime.RuntimeConfig
import org.unitedsoftware.alexandrite.runtime.chat.MemoryChatStateStore
import org.unitedsoftware.alexandrite.runtime.chat.PluginChatStates
import org.unitedsoftware.alexandrite.runtime.chat.UnreadableStateListener
import org.unitedsoftware.alexandrite.runtime.config.configBackup
import org.unitedsoftware.alexandrite.runtime.hook.HookDecisionListener
import org.unitedsoftware.alexandrite.runtime.hook.HookDispatcher
import org.unitedsoftware.alexandrite.runtime.hook.HookFailureListener
import org.unitedsoftware.alexandrite.runtime.i18n.RuntimeTexts
import org.unitedsoftware.alexandrite.runtime.store.MemoryRecordStore
import org.unitedsoftware.alexandrite.runtime.store.PluginRecords
import org.unitedsoftware.alexandrite.runtime.store.UnreadableRecordListener
import org.unitedsoftware.alexandrite.runtime.turn.PluginTurnInitiator
import org.unitedsoftware.alexandrite.sdk.channel.ChannelControl
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditor
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.config.LiveConfig
import org.unitedsoftware.alexandrite.sdk.di.container.Dependency
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.di.container.PluginBindings
import org.unitedsoftware.alexandrite.sdk.di.container.Resolver
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.Hooks
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.plugin.PluginOrigins
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.runtime.RuntimeControl
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import kotlin.coroutines.CoroutineContext

internal const val RUNTIME_PLUGIN = "alexandrite-runtime"

internal const val PLUGINS_DIRECTORY = "plugins"

/**
 * What the runtime itself binds for [plugins], of which [builtIns] are on the built-in list, where [initiated] tells
 * whether a plugin binds the [TurnInitiation] that every plugin's [TurnInitiator] queues its turns with.
 */
internal fun runtimeBindings(
    config: RuntimeConfig,
    configFile: Path?,
    plugins: List<PluginInfo>,
    builtIns: Set<String>,
    hookFailures: HookFailureListener,
    hookDecisions: HookDecisionListener,
    unreadableStates: UnreadableStateListener,
    unreadableRecords: UnreadableRecordListener,
    texts: RuntimeTexts,
    control: (plugin: String) -> RuntimeControl,
    editors: (plugin: String) -> ConfigEditor,
    /** The host config that takes the changes of the [LiveConfig] contributions its argument lists, by plugin. */
    hostConfig: (live: () -> List<Pair<String, LiveConfig>>) -> HostConfig,
    scopes: PluginScopes,
    directory: ChannelDirectory,
    channels: ChannelControl,
    initiated: Boolean,
    context: CoroutineContext,
    agentPlaces: (HostPaths) -> AgentPlaces,
): PluginBindings {
    val hooks = key<Hook>()
    val live = key<LiveConfig>()
    val stateStore = key<ChatStateStore>()
    val recordStore = key<RecordStore>()
    val initiation = key<TurnInitiation>()
    val clock = Clock.system(config.zone)
    val memory by lazy { MemoryChatStateStore() }
    val memoryRecords by lazy { MemoryRecordStore(clock) }
    val paths = RuntimeHostPaths(config, configFile)
    val bindings = listOf(
        binding(
            key<Hooks>(),
            RUNTIME_PLUGIN,
            "Hooks",
            dependencies = listOf(Dependency(hooks, DependencyKind.ALL, "hooks")),
        ) { r ->
            val dispatcher = HookDispatcher(
                r.getAll(hooks),
                hookFailures,
                asyncContext = context,
                decisions = hookDecisions,
                pluginOf = r::pluginOf,
            )
            if (dispatcher.hasAsyncObservers) dispatcher else object : Hooks by dispatcher {}
        },
        instanceBinding(key<Clock>(), clock, RUNTIME_PLUGIN, "Clock"),
        instanceBinding(key<HostPaths>(), paths, RUNTIME_PLUGIN, "HostPaths"),
        instanceBinding(key<AgentPlaces>(), agentPlaces(paths), RUNTIME_PLUGIN, "AgentPlaces"),
        instanceBinding(key<ChannelDirectory>(), directory, RUNTIME_PLUGIN, "ChannelDirectory"),
        instanceBinding(key<ChannelControl>(), channels, RUNTIME_PLUGIN, "ChannelControl"),
        binding(
            key<HostConfig>(),
            RUNTIME_PLUGIN,
            "HostConfig",
            dependencies = listOf(Dependency(live, DependencyKind.ALL, "live")),
            managed = false,
        ) { r -> hostConfig { r.getAll(live).mapNotNull { config -> r.pluginOf(config)?.let { it to config } } } },
        binding(key<PluginOrigins>(), RUNTIME_PLUGIN, "PluginOrigins", managed = false) { r ->
            ResolvedOrigins(r, builtIns)
        },
    ) + plugins.flatMap { plugin ->
        listOf(
            instanceBinding(key<PluginInfo>(plugin.id), plugin, RUNTIME_PLUGIN, "PluginInfo of ${plugin.id}"),
            binding(key<PluginFiles>(plugin.id), RUNTIME_PLUGIN, "PluginFiles of ${plugin.id}", managed = false) {
                PluginDirectories(
                    paths.pluginDataRoot.resolve(plugin.id),
                    config.cacheDir.resolve(PLUGINS_DIRECTORY).resolve(plugin.id),
                )
            },
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
                key<Records>(plugin.id),
                RUNTIME_PLUGIN,
                "Records of ${plugin.id}",
                dependencies = listOf(Dependency(recordStore, DependencyKind.OPTIONAL, "store")),
                managed = false,
            ) { r -> PluginRecords(plugin.id, r.getOrNull(recordStore) ?: memoryRecords, unreadableRecords) },
            binding(
                key<TurnInitiator>(plugin.id),
                RUNTIME_PLUGIN,
                "TurnInitiator of ${plugin.id}",
                dependencies = listOfNotNull(
                    Dependency(initiation, DependencyKind.LAZY, "initiation").takeIf { initiated },
                ),
                managed = false,
            ) { r -> PluginTurnInitiator(plugin.id, if (initiated) r.lazy(initiation) else null) },
            instanceBinding(
                key<Texts>(plugin.id),
                texts.of(plugin.id),
                RUNTIME_PLUGIN,
                "Texts of ${plugin.id}",
            ),
            instanceBinding(
                key<ConfigEditor>(plugin.id),
                editors(plugin.id),
                RUNTIME_PLUGIN,
                "ConfigEditor of ${plugin.id}",
            ),
        )
    }
    return PluginBindings(RUNTIME_PLUGIN, bindings)
}

private class ResolvedOrigins(private val resolver: Resolver, private val builtIns: Set<String>) : PluginOrigins {
    override fun isBuiltIn(instance: Any): Boolean = resolver.pluginOf(instance) in builtIns

    override fun pluginOf(instance: Any): String? = resolver.pluginOf(instance)
}

/** Creates both directories as it is made. */
private class PluginDirectories(override val dataDir: Path, override val cacheDir: Path) : PluginFiles {
    init {
        createOwnerOnly(dataDir)
        createOwnerOnly(cacheDir)
    }
}

private class RuntimeHostPaths(config: RuntimeConfig, given: Path?) : HostPaths {
    override val dataRoot: Path = config.dataDir.absolute()
    override val pluginDataRoot: Path = dataRoot.resolve(PLUGINS_DIRECTORY)
    override val cacheRoot: Path = config.cacheDir.absolute()
    override val configFile: Path? = given?.absolute()
    override val configTarget: Path? = configFile?.let(::followed)
    override val configBackup: Path? = configTarget?.let(::configBackup)
    override val protected: List<Path> = config.protected.map { it.absolute() }.distinct()
}

private fun Path.absolute(): Path = toAbsolutePath().normalize()

private fun followed(file: Path): Path = try {
    file.toRealPath()
} catch (e: IOException) {
    file
}
