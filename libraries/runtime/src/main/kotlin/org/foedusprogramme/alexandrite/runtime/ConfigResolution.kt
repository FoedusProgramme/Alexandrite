package org.foedusprogramme.alexandrite.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.foedusprogramme.alexandrite.runtime.config.configBindings
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.problem.Problem

internal class EnabledPlugin(val entry: PluginEntry, val configBindings: List<Binding<*>>)

internal class ConfigResolution(
    val enabled: List<EnabledPlugin>,
    val disabled: List<DisabledPlugin>,
    val unknownPluginConfig: List<String>,
    val problems: List<Problem>,
)

internal fun resolveConfig(entries: List<PluginEntry>, source: ConfigSource): ConfigResolution {
    val problems = mutableListOf<Problem>()
    val enabled = mutableListOf<PluginEntry>()
    val disabled = mutableListOf<DisabledPlugin>()
    val failedRoots = mutableListOf<String>()
    for (entry in entries.sortedBy { it.id }) {
        val reason = try {
            disabledReason(entry, source)
        } catch (e: ConfigException) {
            problems += invalidConfig(e, entry.id)
            failedRoots += entry.index.configRoot
            continue
        }
        if (reason == null) enabled += entry else disabled += DisabledPlugin(entry.id, reason)
    }
    problems += overlappingRoots(enabled)
    problems += missingRequirements(entries, enabled, disabled)
    val unknown = unknownConfig(entries, source, failedRoots)
    problems += unknown.problems
    val configured = enabled.map { entry ->
        val config = try {
            configBindings(entry.index, source)
        } catch (e: ConfigException) {
            problems += invalidConfig(e, entry.id)
            emptyList()
        }
        EnabledPlugin(entry, config)
    }
    return ConfigResolution(configured, disabled, unknown.pluginPaths, problems)
}

/** Why [entry] is off, null when it is on. */
private fun disabledReason(entry: PluginEntry, source: ConfigSource): DisabledPlugin.Reason? {
    val root = entry.index.configRoot
    val tree = source.tree(root)
    val enabled = tree?.get(PluginIds.ENABLED_KEY)?.let { value ->
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw ConfigException("$root.${PluginIds.ENABLED_KEY}", "must be true or false")
    }
    val optIn = !entry.explicit && entry.row?.layer in OPT_IN_LAYERS
    return when {
        enabled == false -> DisabledPlugin.Reason.ENABLED_FALSE
        tree == null && optIn -> DisabledPlugin.Reason.NOT_CONFIGURED
        else -> null
    }
}

private fun overlappingRoots(enabled: List<PluginEntry>): List<Problem> {
    val roots = enabled.map { it.id to it.index.configRoot }
    return roots.flatMapIndexed { index, (id, root) ->
        roots.drop(index + 1)
            .filter { (_, other) -> root == other || root.startsWith("$other.") || other.startsWith("$root.") }
            .map { (otherId, other) ->
                Problem(
                    RuntimeProblemKind.OVERLAPPING_ROOTS,
                    "Overlapping config roots: plugin '$id' reads '$root' and plugin '$otherId' reads '$other'. " +
                        "Give each plugin a config root of its own.",
                    null,
                    null,
                )
            }
    }
}

private fun missingRequirements(
    entries: List<PluginEntry>,
    enabled: List<PluginEntry>,
    disabled: List<DisabledPlugin>,
): List<Problem> {
    val on = enabled.mapTo(HashSet()) { it.id }
    val off = disabled.associateBy { it.id }
    val roots = entries.associate { it.id to it.index.configRoot }
    return enabled.flatMap { entry ->
        entry.plugin.info.requires.distinct().filter { it !in on }.mapNotNull { required ->
            val root = roots[required]
            val why = when (off[required]?.reason) {
                DisabledPlugin.Reason.ENABLED_FALSE -> "which is disabled by `$root.${PluginIds.ENABLED_KEY} = false`"

                DisabledPlugin.Reason.NOT_CONFIGURED ->
                    "which is not configured. Add a '$root' section to switch it on"

                null -> if (root == null) "which is not loaded. Add it to the plugin set" else return@mapNotNull null
            }
            Problem(
                RuntimeProblemKind.MISSING_REQUIREMENT,
                "Plugin '${entry.id}' requires plugin '$required', $why.",
                entry.id,
                null,
            )
        }
    }
}

private class UnknownConfig(val pluginPaths: List<String>, val problems: List<Problem>)

/** The config paths that no plugin of [entries] reads. */
private fun unknownConfig(entries: List<PluginEntry>, source: ConfigSource, failedRoots: List<String>): UnknownConfig {
    val roots = entries.mapTo(sortedSetOf()) { it.index.configRoot }
    val pluginPaths = mutableListOf<String>()
    val problems = mutableListOf<Problem>()

    fun walk(prefix: String?, keys: Set<String>) {
        for (key in keys.sorted()) {
            val path = if (prefix == null) key else "$prefix.$key"
            when {
                key.isEmpty() || '.' in key -> problems += Problem(
                    RuntimeProblemKind.INVALID_CONFIG,
                    "Invalid config key '$path': a key may not be empty or contain '.'. Nest the objects instead.",
                    null,
                    null,
                )

                path in roots -> Unit

                path == PluginIds.THIRD_PARTY_ROOT || roots.any { it.startsWith("$path.") } -> {
                    val tree = try {
                        source.tree(path)
                    } catch (e: ConfigException) {
                        if (failedRoots.none { it.startsWith("$path.") }) problems += invalidConfig(e, null)
                        null
                    }
                    walk(path, tree?.keys.orEmpty())
                }

                path.startsWith("${PluginIds.THIRD_PARTY_ROOT}.") -> pluginPaths += path

                else -> problems += Problem(
                    RuntimeProblemKind.UNKNOWN_CONFIG,
                    "Unknown config at '$path': no plugin reads it. " +
                        "Config roots of the plugin set: ${roots.joinToString().ifEmpty { "none" }}.",
                    null,
                    null,
                )
            }
        }
    }

    walk(null, source.tree("")?.keys.orEmpty())
    return UnknownConfig(pluginPaths, problems)
}

private fun invalidConfig(e: ConfigException, plugin: String?): Problem =
    Problem(RuntimeProblemKind.INVALID_CONFIG, e.message ?: e.toString(), plugin, null)

private val OPT_IN_LAYERS = setOf(BuiltInLayer.CHANNEL, BuiltInLayer.PROVIDER)
