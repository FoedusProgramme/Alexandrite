package org.foedusprogramme.alexandrite.agent.control

import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import java.util.Locale

/** The command handlers by the names of the commands they claim. */
@Singleton
internal class CommandRegistry(handlers: List<CommandHandler>) {
    private val handlers: Map<String, Pair<CommandHandler, CommandSpec>>

    init {
        val claimed = LinkedHashMap<String, Pair<CommandHandler, CommandSpec>>()
        for (handler in handlers) {
            for (spec in handler.commands) {
                val other = claimed.putIfAbsent(spec.name.lowercase(Locale.ROOT), handler to spec)?.first
                if (other != null) {
                    throw ConfigException(
                        null,
                        "Command '${spec.name}' is claimed by both ${other.javaClass.name} and " +
                            "${handler.javaClass.name}: switch off the plugin of one of them.",
                    )
                }
            }
        }
        this.handlers = claimed
    }

    /** Every claimed command, sorted by name. */
    val commands: List<CommandSpec> get() = handlers.values.map { it.second }.sortedBy { it.name }

    /** The handler that claims the command [name], ignoring case, null when none does. */
    fun handler(name: String): CommandHandler? = handlers[name.lowercase(Locale.ROOT)]?.first
}
