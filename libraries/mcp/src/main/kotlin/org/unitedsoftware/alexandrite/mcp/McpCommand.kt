package org.unitedsoftware.alexandrite.mcp

import org.unitedsoftware.alexandrite.mcp.oauth.OAuthException
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec

/** `/mcp`: anyone sees the servers' states, an operator restarts one and logs in or out of it in a direct chat. */
@Singleton
@Contribute(CommandHandler::class)
internal class McpCommand(private val hub: McpHub, private val texts: Texts) : CommandHandler {
    override val commands: List<CommandSpec> =
        listOf(
            CommandSpec.builder(MCP, texts.text(TextKeys.DESCRIPTION, null))
                .secretArguments(true)
                .operatorsOnly(CommandForms.WITH_ARGUMENTS)
                .build(),
        )

    override fun description(command: String, language: LanguageTag?): String =
        texts.text(TextKeys.DESCRIPTION, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val language = context.turn.language
        val words = invocation.arguments.trim().split(WHITESPACE).filter(String::isNotEmpty)
        val reply = when {
            words.isEmpty() -> list(language)

            words.size == 2 && words[0].equals(RESTART, ignoreCase = true) -> restart(words[1].lowercase(), language)

            (words.size >= 2 && words[0].equals(LOGIN, ignoreCase = true)) ||
                (words.size == 2 && words[0].equals(LOGOUT, ignoreCase = true)) ->
                return account(invocation, context, words, language)

            else -> texts.text(TextKeys.USAGE, language)
        }
        context.reply(reply)
    }

    /** `/mcp login <server> [address]` and `/mcp logout <server>`, whose links and addresses are credentials. */
    private suspend fun account(
        invocation: CommandInvocation,
        context: CommandContext,
        words: List<String>,
        language: LanguageTag?,
    ) {
        val name = words[1].lowercase()
        val server = hub.server(name)
        val refusal = when {
            invocation.chatKind != ChatKind.DIRECT -> texts.text(TextKeys.LOGIN_REFUSED_GROUP, language)
            server == null -> texts.text(TextKeys.UNKNOWN_SERVER, language, "name" to name)
            server.spec.transport !is Transport.Http -> texts.text(TextKeys.LOGIN_NOT_HTTP, language, "name" to name)
            else -> null
        }
        if (refusal != null) {
            context.reply(refusal)
            return
        }
        if (words[0].equals(LOGOUT, ignoreCase = true)) {
            val key = if (hub.oauth.logout(name)) TextKeys.LOGOUT_DONE else TextKeys.LOGOUT_NONE
            context.reply(texts.text(key, language, "name" to name))
            return
        }
        try {
            if (words.size == 2) {
                val login = hub.oauth.start(name)
                val key = if (login.loopback) TextKeys.LOGIN_LINK else TextKeys.LOGIN_LINK_PUBLIC
                context.reply(texts.text(key, language, "name" to name, "url" to login.url), forModel = false)
            } else {
                hub.oauth.complete(name, words.drop(2).joinToString(""))
                context.reply(texts.text(TextKeys.LOGIN_DONE, language, "name" to name), forModel = false)
            }
        } catch (e: OAuthException) {
            val reason = e.message ?: e.toString()
            val text = texts.text(TextKeys.LOGIN_FAILED, language, "name" to name, "reason" to reason)
            context.reply(text, forModel = false)
        }
    }

    private fun list(language: LanguageTag?): String {
        if (hub.servers.isEmpty()) return texts.text(TextKeys.LIST_NONE, language)
        val lines = hub.servers.map { server ->
            val status = server.status.value
            val state = when (status.state) {
                ServerState.CONNECTED -> {
                    val connected = texts.text(
                        TextKeys.STATE_CONNECTED,
                        language,
                        "version" to status.protocolVersion,
                        "tools" to status.tools,
                    )
                    val failing = if (status.serverErrors == 0) {
                        connected
                    } else {
                        texts.text(
                            TextKeys.STATE_FAILING,
                            language,
                            "state" to connected,
                            "count" to status.serverErrors,
                        )
                    }
                    val wider = hub.oauth.widerScopes(server.spec.name)
                    if (wider == null) {
                        failing
                    } else {
                        texts.text(
                            TextKeys.STATE_WIDER,
                            language,
                            "state" to failing,
                            "scope" to wider.joinToString(" "),
                        )
                    }
                }

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
                    "error" to error,
                )
            }
        }
        val list = texts.text(TextKeys.LIST, language, "servers" to lines.joinToString("\n"))
        val waiting = hub.servers.any {
            it.status.value.state == ServerState.NEEDS_LOGIN || hub.oauth.widerScopes(it.spec.name) != null
        }
        return if (waiting) list + "\n\n" + texts.text(TextKeys.LOGIN_HINT, language) else list
    }

    private fun restart(name: String, language: LanguageTag?): String {
        val server = hub.server(name) ?: return texts.text(TextKeys.UNKNOWN_SERVER, language, "name" to name)
        if (!server.spec.enabled) return texts.text(TextKeys.RESTART_DISABLED, language, "name" to name)
        server.restart()
        return texts.text(TextKeys.RESTART_DONE, language, "name" to name)
    }

    private companion object {
        const val MCP = "mcp"
        const val RESTART = "restart"
        const val LOGIN = "login"
        const val LOGOUT = "logout"
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
    const val UNKNOWN_SERVER = "server.unknown"
    const val RESTART_DISABLED = "restart.disabled"
    const val STATE_WIDER = "state.wider"
    const val STATE_FAILING = "state.failing"
    const val LOGIN_HINT = "login.hint"
    const val LOGIN_REFUSED_GROUP = "login.refused_group"
    const val LOGIN_NOT_HTTP = "login.not_http"
    const val LOGIN_LINK = "login.link"
    const val LOGIN_LINK_PUBLIC = "login.link_public"
    const val LOGIN_DONE = "login.done"
    const val LOGIN_FAILED = "login.failed"
    const val LOGOUT_DONE = "logout.done"
    const val LOGOUT_NONE = "logout.none"
}
