package org.foedusprogramme.alexandrite.runtime.channel

import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.container.Scope
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.problem.Problem

/** The problems of the [Channel] contributions of [plugins], whose channel types [types] holds by plugin id. */
internal fun channelProblems(plugins: List<PluginBindings>, types: Map<String, String?>): List<Problem> =
    plugins.flatMap { plugin ->
        val id = plugin.id
        val (scoped, singletons) = plugin.bindings.filter { it.key == CHANNEL && it.multi }
            .partition { it.scope == Scope.CHANNEL_INSTANCE }
        val type = types[id]
        val singletonProblems = singletons.map {
            problem(id, "Singleton channel: ${label(it)} contributes a Channel, which is channel-instance-scoped.")
        }
        val contributors = labels(scoped)
        val countProblem = when {
            type == null && scoped.isNotEmpty() ->
                "Untyped channel: $contributors contributes a Channel, but plugin '$id' declares no channel type."

            type != null && scoped.isEmpty() ->
                "Missing channel: plugin '$id' of channel type '$type' contributes no channel-instance-scoped Channel."

            type != null && scoped.size > 1 ->
                "Several channels: plugin '$id' of channel type '$type' contributes a Channel from $contributors, " +
                    "but each channel instance has exactly one."

            else -> null
        }
        singletonProblems + listOfNotNull(countProblem?.let { problem(id, it) })
    }

private val CHANNEL = key<Channel>()

private fun label(binding: Binding<*>): String = "${binding.origin} (plugin ${binding.plugin})"

private fun labels(bindings: List<Binding<*>>): String = bindings.joinToString(" and ", transform = ::label)

private fun problem(plugin: String, message: String): Problem =
    Problem(RuntimeProblemKind.CHANNEL_CONTRIBUTIONS, message, plugin)
