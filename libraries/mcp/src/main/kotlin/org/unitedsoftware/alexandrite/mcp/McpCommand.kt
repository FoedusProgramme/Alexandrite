package org.unitedsoftware.alexandrite.mcp

import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec

/** `/mcp`: anyone sees the servers' states, an admin restarts one. */
@Singleton
@Contribute(CommandHandler::class)
internal class McpCommand(private val hub: McpHub, private val texts: Texts) : CommandHandler {
    override val commands: List<CommandSpec> =
        listOf(CommandSpec.builder(MCP, texts.text(TextKeys.DESCRIPTION, null)).build())

    override fun description(command: String, language: LanguageTag?): String =
        texts.text(TextKeys.DESCRIPTION, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val language = context.turn.language
        val words = invocation.arguments.trim().split(WHITESPACE).filter(String::isNotEmpty)
        val reply = when {
            words.isEmpty() -> list(language)

            words.size == 2 && words[0].equals(RESTART, ignoreCase = true) -> when {
                !invocation.issuer.isAdmin -> texts.text(TextKeys.REFUSED_ADMIN, language)
                else -> restart(words[1].lowercase(), language)
            }

            else -> texts.text(TextKeys.USAGE, language)
        }
        context.reply(reply)
    }

    private fun list(language: LanguageTag?): String {
        if (hub.servers.isEmpty()) return texts.text(TextKeys.LIST_NONE, language)
        val lines = hub.servers.map { server ->
            val status = server.status.value
            val state = when (status.state) {
                ServerState.CONNECTED -> texts.text(
                    TextKeys.STATE_CONNECTED,
                    language,
                    "version" to status.protocolVersion,
                    "tools" to status.tools,
                )

                ServerState.CONNECTING -> texts.text(TextKeys.STATE_CONNECTING, language)

                ServerState.DOWN -> texts.text(TextKeys.STATE_DOWN, language)

                ServerState.NEEDS_LOGIN -> texts.text(TextKeys.STATE_NEEDS_LOGIN, language)

                ServerState.DISABLED -> texts.text(TextKeys.STATE_DISABLED, language)
            }
            val error = status.lastError
            if (error == null) {
                texts.text(TextKeys.LIST_ITEM, language, "name" to server.spec.name, "state" to state)
            } else {
                texts.text(
                    TextKeys.LIST_ITEM_ERROR,
                    language,
                    "name" to server.spec.name,
                    "state" to state,
                    "error" to cut(error, MAX_ERROR),
                )
            }
        }
        return texts.text(TextKeys.LIST, language, "servers" to lines.joinToString("\n"))
    }

    private fun restart(name: String, language: LanguageTag?): String {
        val server = hub.server(name) ?: return texts.text(TextKeys.RESTART_UNKNOWN, language, "name" to name)
        if (!server.spec.enabled) return texts.text(TextKeys.RESTART_DISABLED, language, "name" to name)
        server.restart()
        return texts.text(TextKeys.RESTART_DONE, language, "name" to name)
    }

    private companion object {
        const val MCP = "mcp"
        const val RESTART = "restart"
        const val MAX_ERROR = 200
        val WHITESPACE = Regex("\\s+")
    }
}

internal object TextKeys {
    const val DESCRIPTION = "command.mcp.description"
    const val USAGE = "usage.mcp"
    const val LIST = "list"
    const val LIST_NONE = "list.none"
    const val LIST_ITEM = "list.item"
    const val LIST_ITEM_ERROR = "list.item_error"
    const val STATE_CONNECTED = "state.connected"
    const val STATE_CONNECTING = "state.connecting"
    const val STATE_DOWN = "state.down"
    const val STATE_NEEDS_LOGIN = "state.needs_login"
    const val STATE_DISABLED = "state.disabled"
    const val RESTART_DONE = "restart.done"
    const val RESTART_UNKNOWN = "restart.unknown"
    const val RESTART_DISABLED = "restart.disabled"
    const val REFUSED_ADMIN = "refused.admin"
}
