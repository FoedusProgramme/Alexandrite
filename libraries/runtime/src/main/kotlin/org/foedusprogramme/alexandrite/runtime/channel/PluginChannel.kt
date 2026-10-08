package org.foedusprogramme.alexandrite.runtime.channel

import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Scope
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.problem.Problem

/** A plugin's channel type, null when it is no channel plugin or has [problems]. */
internal class PluginChannel(val type: ChannelType?, val problems: List<Problem>)

/** The channel of the plugin [id], named by its Channel contribution among [bindings]. */
internal fun pluginChannel(id: String, bindings: List<Binding<*>>): PluginChannel {
    val channels = bindings.filter { it.key == CHANNEL && it.multi }
    val problems = channels.filter { it.scope != Scope.CHANNEL_INSTANCE }.mapTo(mutableListOf()) {
        problem(id, "Singleton channel: ${label(it)} contributes a Channel, which is channel-instance-scoped.")
    }
    val channel = channels.singleOrNull()
    val name = channel?.name
    when {
        channels.size > 1 -> problems += problem(
            id,
            "Several channels: plugin '$id' contributes a Channel from ${labels(channels)}, but a channel plugin " +
                "contributes exactly one.",
        )

        channel == null -> Unit

        name == null -> problems += problem(
            id,
            "Unnamed channel: ${label(channel)} contributes a Channel without a name, which is its channel type.",
        )

        !PluginIds.PATTERN.matches(name) -> problems += Problem(
            RuntimeProblemKind.MALFORMED_CHANNEL_TYPE,
            "Malformed channel type '$name' of plugin '$id', the name of its Channel ${channel.origin}: a channel " +
                "type is lowercase words of letters and digits, each starting with a letter, joined by single " +
                "hyphens, such as \"telegram\". Rebuild the plugin with the Alexandrite KSP processor.",
            id,
        )
    }
    return PluginChannel(name?.takeIf { problems.isEmpty() }?.let(::ChannelType), problems)
}

private val CHANNEL = key<Channel>()

private fun label(binding: Binding<*>): String = "${binding.origin} (plugin ${binding.plugin})"

private fun labels(bindings: List<Binding<*>>): String = bindings.joinToString(" and ", transform = ::label)

private fun problem(plugin: String, message: String): Problem =
    Problem(RuntimeProblemKind.CHANNEL_CONTRIBUTIONS, message, plugin)
