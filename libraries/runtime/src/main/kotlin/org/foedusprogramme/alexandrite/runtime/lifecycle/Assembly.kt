package org.foedusprogramme.alexandrite.runtime.lifecycle

import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.config.ConfigResolution
import org.foedusprogramme.alexandrite.runtime.config.EnabledPlugin
import org.foedusprogramme.alexandrite.runtime.config.resolveConfig
import org.foedusprogramme.alexandrite.runtime.hook.HookFailureListener
import org.foedusprogramme.alexandrite.runtime.plugin.pluginProblems
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.sdk.di.container.Container
import org.foedusprogramme.alexandrite.sdk.di.container.DiException
import org.foedusprogramme.alexandrite.sdk.di.container.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.container.Scope
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** The work of the start stages from DATA_DIR to GRAPH. */
internal class Assembly(
    private val spec: RuntimeSpec,
    private val control: RuntimeControl,
    private val scopes: PluginScopes,
    private val context: CoroutineContext,
) {
    private val name = spec.config.name

    private val hookFailures = HookFailureListener { hook, point, failure ->
        val error = (failure as? HookFailure.Threw)?.error
        logger.warn("{}: hook {} failed at '{}': {}", name, hook.javaClass.name, point, failure, error)
    }

    fun lockDataDir(): DataDirLock {
        val dataDir = spec.config.dataDir
        return try {
            DataDirLock.acquire(dataDir) { holder ->
                val message = "Data directory '$dataDir' is in use: $holder holds its lock file " +
                    "${DataDirLock.FILE_NAME}. Stop that runtime or give this one another data directory."
                val problem = Problem(RuntimeProblemKind.DATA_DIR_LOCKED, message, null, null)
                throw startFailure(name, StartStage.DATA_DIR, listOf(problem))
            }
        } catch (e: IOException) {
            throw startFailure(name, StartStage.DATA_DIR, cause = e)
        }
    }

    fun createCacheDir() {
        try {
            createOwnerOnly(spec.config.cacheDir)
        } catch (e: IOException) {
            throw startFailure(name, StartStage.DATA_DIR, cause = e)
        }
    }

    fun checkPlugins() {
        val plugins = spec.plugins
        plugins.unlisted.forEach {
            logger.warn("{}: not loading {}: the index is neither built in nor added to the plugin set", name, it)
        }
        pluginProblems(plugins).takeIf { it.isNotEmpty() }?.let { throw startFailure(name, StartStage.PLUGINS, it) }
    }

    fun resolvePlugins(): ConfigResolution {
        val resolution = try {
            resolveConfig(spec.plugins.members, spec.pluginConfig)
        } catch (e: Exception) {
            throw startFailure(name, StartStage.CONFIG, cause = e)
        }
        resolution.unknownPluginConfig.forEach {
            logger.warn("{}: ignoring the config at '{}': no plugin of the plugin set reads it", name, it)
        }
        if (resolution.problems.isNotEmpty()) throw startFailure(name, StartStage.CONFIG, resolution.problems)
        logger.info(
            "{}: loading plugins [{}], disabled [{}]",
            name,
            resolution.enabled.joinToString { it.member.id },
            resolution.disabled.joinToString { "${it.id} (${it.reason})" },
        )
        return resolution
    }

    fun pluginBindings(enabled: List<EnabledPlugin>): List<PluginBindings> = try {
        enabled.map { PluginBindings(it.member.id, it.member.index.bindings() + it.configBindings) }
    } catch (e: Exception) {
        throw startFailure(name, StartStage.GRAPH, cause = e)
    }

    /** The container of [plugins] and of what the runtime binds for [enabled]. */
    fun container(enabled: List<EnabledPlugin>, plugins: List<PluginBindings>): Container = try {
        val infos = enabled.map { it.member.plugin.info }
        Container.build(plugins + runtimeBindings(spec.config, infos, hookFailures, control, scopes, context))
    } catch (e: DiException) {
        throw startFailure(name, StartStage.GRAPH, e.problems, e)
    } catch (e: Exception) {
        throw startFailure(name, StartStage.GRAPH, cause = e)
    }

    fun checkChannelInstances(container: Container, plugins: List<PluginBindings>) {
        val problems = try {
            plugins.filter { plugin -> plugin.bindings.any { it.scope == Scope.CHANNEL_INSTANCE } }
                .flatMap { container.validateChild(setOf(it.id)) }
        } catch (e: Exception) {
            throw startFailure(name, StartStage.GRAPH, cause = e)
        }
        if (problems.isNotEmpty()) throw startFailure(name, StartStage.GRAPH, problems)
    }
}

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)
