package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.app.control.ControlReply
import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.io.IOException

private const val AGENTS = "agent.agents"
private const val TELEGRAM_INSTANCES = "channels.telegram.instances"
private const val MCP_SERVERS = "mcp.servers"
private const val TELEGRAM_API = "https://api.telegram.org"

internal fun requireId(id: String, what: String): String {
    if (!PluginIds.PATTERN.matches(id)) {
        throw UsageException("'$id' is no $what: use lowercase letters, digits and -, such as main.")
    }
    return id
}

internal fun requireModel(text: String): String {
    ModelRef.parseOrNull(text) ?: throw UsageException("'$text' is no model: write <endpoint>/<model>.")
    return text
}

// Agents.

/** An agent of the config file: its [id], its models and the channel instances it serves. */
private class AgentEntry(val id: String, val config: JsonObject) {
    val name: String? get() = config["name"].text

    val models: List<String>
        get() = (config["models"] as? JsonArray)?.mapNotNull { it.text }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(config["model"].text)

    val channels: JsonObject get() = config["channels"] as? JsonObject ?: JsonObject(emptyMap())

    val isDefault: Boolean get() = channels.values.any { (it as? JsonObject)?.get("default") == JsonPrimitive(true) }
}

private fun agents(config: JsonObject): List<AgentEntry> =
    config.objectAt(AGENTS).mapNotNull { (id, value) -> (value as? JsonObject)?.let { AgentEntry(id, it) } }

internal fun agentList(cli: Cli, json: Boolean): Int {
    val agents = agents(cli.rawConfig() ?: return ExitCode.CONFIG)
    if (json) {
        cli.printJson(
            JsonArray(
                agents.map { agent ->
                    buildJsonObject {
                        put("id", agent.id)
                        put("name", agent.name)
                        put("models", JsonArray(agent.models.map(::JsonPrimitive)))
                        put("channels", agent.channels)
                    }
                },
            ),
        )
        return ExitCode.OK
    }
    if (agents.isEmpty()) return cli.done("No agent is configured: add one with alexandrite agent add <id>.")
    for (agent in agents) {
        val channels = agent.channels.map { (instance, attachment) ->
            val notes = listOfNotNull(
                "default".takeIf { (attachment as? JsonObject)?.get("default") == JsonPrimitive(true) },
                (attachment as? JsonObject)?.get("home").text?.let { "home $it" },
            )
            if (notes.isEmpty()) instance else "$instance (${notes.joinToString()})"
        }
        val parts = listOfNotNull(
            agent.name?.let { "\"$it\"" },
            "model ${agent.models.joinToString().ifEmpty { "none" }}",
            "serves ${channels.joinToString().ifEmpty { "no channel" }}",
        )
        cli.out.println("${agent.id}  ${parts.joinToString(", ")}")
    }
    return ExitCode.OK
}

internal fun agentAdd(
    cli: Cli,
    id: String,
    name: String?,
    description: String?,
    model: String?,
    channels: List<String>,
    default: Boolean,
    restart: Boolean,
): Int {
    val config = cli.rawConfig() ?: return ExitCode.CONFIG
    if (agents(config).any { it.id == id }) return cli.fail("Agent $id is configured already.")
    model?.let { unknownEndpoint(config, it)?.let { problem -> return cli.fail(problem) } }
    for (channel in channels) unknownInstance(config, channel)?.let { return cli.fail(it) }
    val agent = buildJsonObject {
        name?.let { put("name", it) }
        description?.let { put("description", it) }
        model?.let { put("model", it) }
        if (channels.isNotEmpty()) {
            putJsonObject("channels") {
                for (channel in channels) putJsonObject(channel) { if (default) put("default", true) }
            }
        }
    }
    return changeConfig(cli, listOf(ConfigChangeView("$AGENTS.$id", agent)), restart)
}

internal fun agentRemove(cli: Cli, id: String, restart: Boolean): Int {
    val config = cli.rawConfig() ?: return ExitCode.CONFIG
    if (agents(config).none { it.id == id }) return cli.fail("No agent $id is configured.")
    return changeConfig(cli, listOf(ConfigChangeView("$AGENTS.$id", remove = true)), restart)
}

/** Shows the models of the agents, or of [agentId], or makes [model] the default model of the agent. */
internal fun model(cli: Cli, model: String?, agentId: String?, json: Boolean, restart: Boolean): Int {
    val config = cli.rawConfig() ?: return ExitCode.CONFIG
    val agents = agents(config)
    if (agentId != null && agents.none { it.id == agentId }) return cli.fail("No agent $agentId is configured.")
    if (model == null) {
        val shown = agents.filter { agentId == null || it.id == agentId }
        if (json) {
            cli.printJson(
                buildJsonObject { shown.forEach { put(it.id, JsonArray(it.models.map(::JsonPrimitive))) } },
            )
        } else if (shown.isEmpty()) {
            cli.out.println("No agent is configured: add one with alexandrite agent add <id>.")
        } else {
            shown.forEach { cli.out.println("${it.id}: ${it.models.joinToString().ifEmpty { "no model" }}") }
        }
        return ExitCode.OK
    }
    val agent = agentId?.let { id -> agents.first { it.id == id } }
        ?: agents.singleOrNull()
        ?: agents.filter { it.isDefault }.singleOrNull()
        ?: return cli.fail(
            if (agents.isEmpty()) {
                "No agent is configured: add one with alexandrite agent add <id> --model $model."
            } else {
                "Name the agent with --agent: ${agents.joinToString { it.id }}."
            },
            ExitCode.USAGE,
        )
    unknownEndpoint(config, model)?.let { return cli.fail(it) }
    val listed = (agent.config["models"] as? JsonArray)?.mapNotNull { it.text }.orEmpty()
    val change = if (listed.isEmpty()) {
        ConfigChangeView("$AGENTS.${agent.id}.model", JsonPrimitive(model))
    } else {
        ConfigChangeView(
            "$AGENTS.${agent.id}.models",
            JsonArray((listOf(model) + (listed - model)).map(::JsonPrimitive)),
        )
    }
    return changeConfig(cli, listOf(change), restart)
}

/** Why [model] names an endpoint the config file does not configure, null when it does. */
private fun unknownEndpoint(config: JsonObject, model: String): String? {
    val endpoint = model.substringBefore('/')
    val endpoints = config.objectAt("providers").values.flatMap { provider ->
        ((provider as? JsonObject)?.get("endpoints") as? JsonObject)?.keys.orEmpty()
    }
    if (endpoint in endpoints) return null
    val known = if (endpoints.isEmpty()) "none is configured" else "the configured ones are ${endpoints.joinToString()}"
    return "No endpoint $endpoint is configured below providers: $known."
}

/** Why [instance] names no channel instance of the config file, null when it names one. */
private fun unknownInstance(config: JsonObject, instance: String): String? {
    val id = ChannelInstanceId.parseOrNull(instance)
        ?: return "'$instance' is no channel instance: write <type>:<name>, such as telegram:main."
    if (config.at("channels.${id.type}.instances.${id.name}") != null) return null
    return "No channel instance $instance is configured: add it with alexandrite channel add ${id.type} ${id.name}."
}

// Channels.

/** Adds the Telegram bot whose token the operator types as the instance [name], served by an agent. */
internal fun channelAddTelegram(
    cli: Cli,
    name: String,
    admins: List<Long>,
    agentId: String?,
    variable: String?,
    apiBaseUrl: String?,
    restart: Boolean,
): Int {
    val config = cli.rawConfig() ?: return ExitCode.CONFIG
    if (config.at("$TELEGRAM_INSTANCES.$name") != null) {
        return cli.fail("The Telegram instance $name is configured already.")
    }
    val agents = agents(config)
    if (agentId != null && agents.none { it.id == agentId }) return cli.fail("No agent $agentId is configured.")
    val agent = agentId ?: agents.singleOrNull()?.id
    val token = cli.machine.askSecret("Telegram bot token from @BotFather (not shown): ")?.trim()
        ?.takeIf { it.isNotEmpty() } ?: return cli.fail("No token was given.")
    val bot = when (val checked = telegramBot(cli, apiBaseUrl ?: TELEGRAM_API, token)) {
        is BotCheck.Bot -> checked
        is BotCheck.Refused -> return cli.fail(checked.reason)
    }
    cli.out.println("The token belongs to ${bot.described}.")
    val secret = variable ?: "ALEXANDRITE_TELEGRAM_${name.uppercase().replace('-', '_')}_TOKEN"
    val secrets = try {
        readSecrets(cli.secrets)
    } catch (e: IOException) {
        return cli.fail("Cannot read ${cli.secrets}: ${e.message ?: e}")
    }
    try {
        writeSecrets(cli.secrets, secrets + (secret to token))
    } catch (e: IOException) {
        return cli.fail("Cannot write ${cli.secrets}: ${e.message ?: e}")
    }
    val instance = buildJsonObject {
        put("botToken", "\${env:$secret}")
        put("adminUserIds", buildJsonArray { admins.forEach { add(it) } })
        apiBaseUrl?.let { put("apiBaseUrl", it) }
    }
    val changes = listOfNotNull(
        ConfigChangeView("$TELEGRAM_INSTANCES.$name", instance),
        agent?.let { ConfigChangeView("$AGENTS.$it.channels.telegram:$name", JsonObject(emptyMap())) },
    )
    val code = changeConfig(cli, changes, restart)
    if (code != ExitCode.OK) {
        try {
            writeSecrets(cli.secrets, secrets)
        } catch (e: IOException) {
            cli.err.println("alexandrite: Cannot take $secret out of ${cli.secrets} again: ${e.message ?: e}")
        }
        return code
    }
    cli.out.println("Saved the token as $secret in ${cli.secrets}.")
    if (agent == null) {
        cli.out.println(
            "No agent serves telegram:$name yet: add it to one with alexandrite config set " +
                "$AGENTS.<id>.channels.telegram:$name {}.",
        )
    }
    if (admins.isEmpty()) {
        cli.out.println(
            "No admin is set: add your Telegram user id with alexandrite config set " +
                "$TELEGRAM_INSTANCES.$name.adminUserIds [<id>].",
        )
    }
    return ExitCode.OK
}

/** What Telegram says about a bot token. */
internal sealed interface BotCheck {
    class Bot(val id: Long, val username: String?) : BotCheck {
        val described: String get() = username?.let { "the bot @$it ($id)" } ?: "the bot $id"
    }

    class Refused(val reason: String) : BotCheck
}

/** Asks the Bot API at [base] whom [token] belongs to. */
internal fun telegramBot(cli: Cli, base: String, token: String): BotCheck {
    val fetched = try {
        cli.machine.get("${base.trimEnd('/')}/bot$token/getMe")
    } catch (e: IOException) {
        return BotCheck.Refused("Cannot reach Telegram at $base: ${(e.message ?: "$e").replace(token, "***")}")
    }
    if (fetched.status == 401 || fetched.status == 404) return BotCheck.Refused("Telegram refuses the bot token.")
    if (fetched.status != 200) return BotCheck.Refused("Telegram answers HTTP ${fetched.status} to the bot token.")
    val result = try {
        (Json.parseToJsonElement(fetched.body) as? JsonObject)?.get("result") as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    } ?: return BotCheck.Refused("Telegram's answer to getMe holds no bot.")
    val id = (result["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        ?: return BotCheck.Refused("Telegram's answer to getMe holds no bot id.")
    return BotCheck.Bot(id, result["username"].text)
}

// MCP servers.

internal fun mcpAdd(cli: Cli, name: String, url: String?, command: List<String>, restart: Boolean): Int {
    val config = cli.rawConfig() ?: return ExitCode.CONFIG
    if (config.at("$MCP_SERVERS.$name") != null) return cli.fail("The MCP server $name is configured already.")
    val server = buildJsonObject {
        if (url != null) {
            put("url", url)
        } else {
            put("command", command.first())
            put("args", JsonArray(command.drop(1).map(::JsonPrimitive)))
        }
    }
    return changeConfig(cli, listOf(ConfigChangeView("$MCP_SERVERS.$name", server)), restart)
}

internal fun mcpRemove(cli: Cli, name: String, restart: Boolean): Int {
    val config = cli.rawConfig() ?: return ExitCode.CONFIG
    if (config.at("$MCP_SERVERS.$name") == null) return cli.fail("No MCP server $name is configured.")
    return changeConfig(cli, listOf(ConfigChangeView("$MCP_SERVERS.$name", remove = true)), restart)
}

internal fun mcpList(cli: Cli, json: Boolean): Int {
    val reply = try {
        cli.ask(ControlRequest.McpServers)
    } catch (e: IOException) {
        return cli.fail("Cannot reach the running Alexandrite: ${e.message ?: e}")
    }
    val servers: List<JsonObject> = when (reply) {
        is ControlReply.McpServers -> reply.servers.map { server ->
            buildJsonObject {
                put("name", server.name)
                put("transport", server.transport)
                put("state", server.state)
                put("tools", server.tools)
                put("lastError", server.lastError)
            }
        }

        null, is ControlReply.Failure -> {
            val config = cli.rawConfig() ?: return ExitCode.CONFIG
            config.objectAt(MCP_SERVERS).map { (name, value) ->
                val server = value as? JsonObject ?: JsonObject(emptyMap())
                buildJsonObject {
                    put("name", name)
                    put("transport", if ("url" in server) "http" else "stdio")
                    put("state", if (server["enabled"] == JsonPrimitive(false)) "disabled" else "configured")
                }
            }
        }

        else -> return cli.fail("The running Alexandrite gave an unexpected answer.")
    }
    if (json) {
        cli.printJson(JsonArray(servers))
        return ExitCode.OK
    }
    if (servers.isEmpty()) return cli.done("No MCP server is configured: add one with alexandrite mcp add.")
    for (server in servers) {
        val tools = (server["tools"] as? JsonPrimitive)?.content?.let { ", $it tools" }.orEmpty()
        val error = server["lastError"].text?.let { "; last error: $it" }.orEmpty()
        cli.out.println("${server["name"].text}  ${server["transport"].text}, ${server["state"].text}$tools$error")
    }
    return ExitCode.OK
}

/** Logs in to [server], completing with [address] when given, else with what the operator pastes. */
internal fun mcpLogin(cli: Cli, server: String, address: String?): Int {
    if (address != null) return mcpAnswered(cli, server, ControlRequest.McpComplete(server, address))
    val started = when (val reply = mcpAsk(cli, ControlRequest.McpLogin(server)) ?: return ExitCode.UNAVAILABLE) {
        is ControlReply.McpLoginStarted -> reply
        is ControlReply.Failure -> return cli.fail(reply.message)
        else -> return cli.fail("The running Alexandrite gave an unexpected answer.")
    }
    cli.out.println("Open this address in a browser to log in to the MCP server $server:")
    cli.out.println(started.url)
    if (!cli.machine.interactive) {
        return cli.done(
            if (started.loopback) {
                "Alexandrite completes the login when the browser comes back to it."
            } else {
                "Then run alexandrite mcp login $server --address '<the address the browser ended on>'."
            },
        )
    }
    val pasted = cli.machine.ask(
        "Paste the address the browser ended on, or press Enter once it says the login is done: ",
    )?.trim().orEmpty()
    if (pasted.isNotEmpty()) return mcpAnswered(cli, server, ControlRequest.McpComplete(server, pasted))
    val state = (mcpAsk(cli, ControlRequest.McpServers) as? ControlReply.McpServers)?.servers
        ?.firstOrNull { it.name == server }?.state
    return if (state == "needs_login") {
        cli.fail("The MCP server $server still waits for a login.")
    } else {
        cli.done("The MCP server $server is $state.")
    }
}

internal fun mcpLogout(cli: Cli, server: String): Int = mcpAnswered(cli, server, ControlRequest.McpLogout(server))

private fun mcpAnswered(cli: Cli, server: String, request: ControlRequest): Int =
    when (val reply = mcpAsk(cli, request) ?: return ExitCode.UNAVAILABLE) {
        is ControlReply.McpLoggedIn -> cli.done("The MCP server $server is logged in and connects again.")

        is ControlReply.McpLoggedOut ->
            cli.done(if (reply.had) "Logged out of the MCP server $server." else "The MCP server $server had no login.")

        is ControlReply.Failure -> cli.fail(reply.message)

        else -> cli.fail("The running Alexandrite gave an unexpected answer.")
    }

/** The running app's answer to [request], null after printing that none runs or why it failed. */
private fun mcpAsk(cli: Cli, request: ControlRequest): ControlReply? = try {
    cli.ask(request) ?: run {
        cli.fail("MCP logins go through the running Alexandrite, and none runs: start it first.", ExitCode.UNAVAILABLE)
        null
    }
} catch (e: IOException) {
    cli.fail("Cannot reach the running Alexandrite: ${e.message ?: e}")
    null
}
