package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.runtime.hook.HookFailureListener
import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.DiException
import org.foedusprogramme.alexandrite.sdk.di.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.Scope
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException

/** Locks the data directory and builds the container of a runtime from [spec]. */
internal class Assembly(
    private val spec: RuntimeSpec,
    private val control: RuntimeControl,
    private val resolved: (RuntimeEvent.PluginsResolved) -> Unit,
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

    /** Runs the PLUGINS, CONFIG and GRAPH stages. */
    fun assemble(startup: Startup): Container {
        startup.stage = StartStage.PLUGINS
        val plugins = spec.plugins
        plugins.unlisted.forEach {
            logger.warn("{}: not loading {}: the index is neither built in nor added to the plugin set", name, it)
        }
        pluginProblems(plugins).takeIf { it.isNotEmpty() }?.let { throw startFailure(name, StartStage.PLUGINS, it) }
        startup.stage = StartStage.CONFIG
        val resolution = try {
            resolveConfig(plugins.entries, spec.pluginConfig)
        } catch (e: Exception) {
            throw startFailure(name, StartStage.CONFIG, cause = e)
        }
        resolution.unknownPluginConfig.forEach {
            logger.warn("{}: ignoring the config at '{}': no plugin of the plugin set reads it", name, it)
        }
        if (resolution.problems.isNotEmpty()) throw startFailure(name, StartStage.CONFIG, resolution.problems)
        val loaded = resolution.enabled.map { it.entry.plugin }
        logger.info(
            "{}: loading plugins [{}], disabled [{}]",
            name,
            loaded.joinToString { it.info.id },
            resolution.disabled.joinToString { "${it.id} (${it.reason})" },
        )
        val unknown = resolution.unknownPluginConfig
        resolved(RuntimeEvent.PluginsResolved(loaded, resolution.disabled, plugins.unlisted, unknown))
        startup.stage = StartStage.GRAPH
        return build(startup, resolution.enabled)
    }

    private fun build(startup: Startup, enabled: List<EnabledPlugin>): Container {
        val plugins = try {
            enabled.map { PluginBindings(it.entry.id, it.entry.index.bindings() + it.configBindings) }
        } catch (e: Exception) {
            throw startFailure(name, StartStage.GRAPH, cause = e)
        }
        val container = try {
            val infos = enabled.map { it.entry.plugin.info }
            Container.build(plugins + runtimeBindings(spec.config, infos, hookFailures, control))
        } catch (e: DiException) {
            throw startFailure(name, StartStage.GRAPH, e.problems, e)
        } catch (e: Exception) {
            throw startFailure(name, StartStage.GRAPH, cause = e)
        }
        startup.container = container
        val problems = try {
            plugins.filter { plugin -> plugin.bindings.any { it.scope == Scope.CHANNEL_INSTANCE } }
                .flatMap { container.validateChild(setOf(it.id)) }
        } catch (e: Exception) {
            throw startFailure(name, StartStage.GRAPH, cause = e)
        }
        if (problems.isNotEmpty()) throw startFailure(name, StartStage.GRAPH, problems)
        return container
    }
}

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)
