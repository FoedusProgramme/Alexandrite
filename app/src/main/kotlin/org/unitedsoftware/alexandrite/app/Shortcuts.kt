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

internal const val AGENTS = "agent.agents"
internal const val TELEGRAM_INSTANCES = "channels.telegram.instances"
private const val MCP_SERVERS = "mcp.servers"

/** [id] when it is a valid id, else a [UsageException] naming it the [what] text. */
internal fun requireId(id: String, what: String): String {
    if (!PluginIds.PATTERN.matches(id)) throw UsageException("usage.no_id", "id" to id, "what" to Text(what))
    return id
}

internal fun requireModel(text: String): String {
    ModelRef.parseOrNull(text) ?: throw UsageException("usage.no_model", "model" to text)
    return text
}

// Agents.

/** An agent of the config file: its [id], its models and the channel instances it serves. */
internal class AgentEntry(val id: String, val config: JsonObject) {
    val name: String? get() = config["name"].text

    val models: List<String>
        get() = (config["models"] as? JsonArray)?.mapNotNull { it.text }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(config["model"].text)

    val channels: JsonObject get() = config["channels"] as? JsonObject ?: JsonObject(emptyMap())

    val isDefault: Boolean get() = channels.values.any { (it as? JsonObject)?.get("default") == JsonPrimitive(true) }
}

internal fun agents(config: JsonObject): List<AgentEntry> =
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
    if (agents.isEmpty()) return cli.done(cli.t("agent.none"))
    for (agent in agents) {
        val channels = agent.channels.map { (instance, attachment) ->
            val notes = listOfNotNull(
                cli.t("agent.default").takeIf { (attachment as? JsonObject)?.get("default") == JsonPrimitive(true) },
                (attachment as? JsonObject)?.get("home").text?.let { cli.t("agent.home", "chat" to it) },
            )
            if (notes.isEmpty()) instance else "$instance (${notes.joinToString()})"
        }
        val parts = listOfNotNull(
            agent.name?.let { "\"$it\"" },
            cli.t("agent.models", "models" to agent.models.joinToString().ifEmpty { cli.t("agent.no_model") }),
            cli.t("agent.serves", "channels" to channels.joinToString().ifEmpty { cli.t("agent.no_channel") }),
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
    if (agents(config).any { it.id == id }) return cli.fail(cli.t("agent.exists", "agent" to id))
    model?.let { unknownEndpoint(config, it)?.let { problem -> return cli.fail(cli.texts(problem)) } }
    for (channel in channels) unknownInstance(config, channel)?.let { return cli.fail(cli.texts(it)) }
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
    if (agents(config).none { it.id == id }) return cli.fail(cli.t("agent.unknown", "agent" to id))
    return changeConfig(cli, listOf(ConfigChangeView("$AGENTS.$id", remove = true)), restart)
}

/** Shows the models of the agents, or of [agentId], or makes [model] the default model of the agent. */
internal fun model(cli: Cli, model: String?, agentId: String?, json: Boolean, restart: Boolean): Int {
    val config = cli.rawConfig() ?: return ExitCode.CONFIG
    val agents = agents(config)
    if (agentId != null && agents.none { it.id == agentId }) {
        return cli.fail(cli.t("agent.unknown", "agent" to agentId))
    }
    if (model == null) {
        val shown = agents.filter { agentId == null || it.id == agentId }
        if (json) {
            cli.printJson(
                buildJsonObject { shown.forEach { put(it.id, JsonArray(it.models.map(::JsonPrimitive))) } },
            )
        } else if (shown.isEmpty()) {
            cli.out.println(cli.t("agent.none"))
        } else {
            shown.forEach { cli.out.println("${it.id}: ${it.models.joinToString().ifEmpty { cli.t("model.none") }}") }
        }
        return ExitCode.OK
    }
    val agent = agentId?.let { id -> agents.first { it.id == id } }
        ?: agents.singleOrNull()
        ?: agents.filter { it.isDefault }.singleOrNull()
        ?: return cli.fail(
            if (agents.isEmpty()) {
                cli.t("model.no_agent", "model" to model)
            } else {
                cli.t(
                    "model.name_agent",
                    "agents" to agents.joinToString { it.id },
                )
            },
            ExitCode.USAGE,
        )
    unknownEndpoint(config, model)?.let { return cli.fail(cli.texts(it)) }
    return changeConfig(cli, defaultModels(agent.id, agent.config, listOf(model)), restart)
}

/** The changes that put [models] first among the models of agent [id] configured as [agent]. */
internal fun defaultModels(id: String, agent: JsonObject?, models: List<String>): List<ConfigChangeView> {
    val listed = (agent?.get("models") as? JsonArray)?.mapNotNull { it.text }.orEmpty()
    val all = (models + (listed - models.toSet())).distinct()
    if (listed.isEmpty() &&
        all.size == 1
    ) {
        return listOf(ConfigChangeView("$AGENTS.$id.model", JsonPrimitive(all.single())))
    }
    return listOfNotNull(
        ConfigChangeView("$AGENTS.$id.models", JsonArray(all.map(::JsonPrimitive))),
        ConfigChangeView("$AGENTS.$id.model", remove = true).takeIf { agent?.get("model") != null },
    )
}

/** Why [model] names an endpoint the config file does not configure, null when it does. */
private fun unknownEndpoint(config: JsonObject, model: String): Text? {
    val endpoint = model.substringBefore('/')
    val endpoints = config.objectAt("providers").values.flatMap { provider ->
        ((provider as? JsonObject)?.get("endpoints") as? JsonObject)?.keys.orEmpty()
    }
    if (endpoint in endpoints) return null
    val known = if (endpoints.isEmpty()) {
        Text("model.no_endpoints")
    } else {
        Text("model.endpoints", "endpoints" to endpoints.joinToString())
    }
    return Text("model.unknown_endpoint", "endpoint" to endpoint, "known" to known)
}

/** Why [instance] names no channel instance of the config file, null when it names one. */
private fun unknownInstance(config: JsonObject, instance: String): Text? {
    val id = ChannelInstanceId.parseOrNull(instance) ?: return Text("channel.no_instance_id", "instance" to instance)
    if (config.at("channels.${id.type}.instances.${id.name}") != null) return null
    return Text("channel.unknown_instance", "instance" to instance, "type" to id.type, "name" to id.name)
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
): Int = cli.onTerminal { addTelegram(cli, name, admins, agentId, variable, apiBaseUrl, restart) }

private fun addTelegram(
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
        return cli.fail(cli.t("channel.exists", "instance" to name))
    }
    val agents = agents(config)
    if (agentId != null && agents.none { it.id == agentId }) {
        return cli.fail(cli.t("agent.unknown", "agent" to agentId))
    }
    val agent = agentId ?: agents.singleOrNull()?.id
    val base = apiBaseUrl ?: TELEGRAM_API
    val bot = askBot(cli, base) ?: return ExitCode.FAILURE
    configuredBot(cli, config, bot.second)?.let { return cli.fail(cli.t("channel.same_bot", "instance" to it)) }
    val ids = admins.ifEmpty { listOf((claimAdmin(cli, base, bot.second, bot.first) ?: return ExitCode.FAILURE).id) }
    val secret = variable ?: telegramVariable(name)
    val secrets = try {
        readSecrets(cli.secrets)
    } catch (e: IOException) {
        return cli.fail(cli.t("secret.unreadable", "file" to cli.secrets, "reason" to (e.message ?: e)))
    }
    try {
        writeSecrets(cli.secrets, secrets + (secret to bot.second))
    } catch (e: IOException) {
        return cli.fail(cli.t("secret.unwritable", "file" to cli.secrets, "reason" to (e.message ?: e)))
    }
    val changes = telegramChanges(name, secret, ids, agent, apiBaseUrl)
    val code = changeConfig(cli, changes, restart)
    if (code != ExitCode.OK) {
        try {
            writeSecrets(cli.secrets, secrets)
        } catch (e: IOException) {
            cli.fail(
                cli.t("secret.not_restored", "name" to secret, "file" to cli.secrets, "reason" to (e.message ?: e)),
            )
        }
        return code
    }
    cli.ui.good(cli.t("channel.saved", "name" to secret, "file" to cli.secrets))
    if (agent == null) cli.ui.warn(cli.t("channel.no_agent", "instance" to name))
    return ExitCode.OK
}

internal fun telegramVariable(name: String): String = "ALEXANDRITE_TELEGRAM_${name.uppercase().replace('-', '_')}_TOKEN"

/**
 * The changes that add the Telegram instance [name] whose token [secret] holds, with [admins] as its admins, their
 * direct chats paired and the first one the home of [agent].
 */
internal fun telegramChanges(
    name: String,
    secret: String,
    admins: List<Long>,
    agent: String?,
    apiBaseUrl: String?,
): List<ConfigChangeView> {
    val instance = buildJsonObject {
        put("botToken", "\${env:$secret}")
        put("adminUserIds", buildJsonArray { admins.forEach { add(it) } })
        put("allowedChats", buildJsonArray { admins.forEach { add(it) } })
        apiBaseUrl?.let { put("apiBaseUrl", it) }
    }
    val attachment = buildJsonObject { admins.firstOrNull()?.let { put("home", "$it") } }
    return listOfNotNull(
        ConfigChangeView("$TELEGRAM_INSTANCES.$name", instance),
        agent?.let { ConfigChangeView("$AGENTS.$it.channels.telegram:$name", attachment) },
    )
}

/** The token typed after a prompt and the bot Telegram says it belongs to, null after printing why there is none. */
internal fun askBot(cli: Cli, base: String, given: String? = null): Pair<BotCheck.Bot, String>? {
    val prompt = cli.t(if (cli.ui.rich) "channel.token_title" else "channel.token_prompt")
    val token = (given ?: cli.ui.secret(prompt))?.trim()?.takeIf { it.isNotEmpty() }
        ?: run {
            cli.fail(cli.t("channel.no_token"))
            return null
        }
    return when (val checked = cli.ui.busy({ cli.t("bot.checking") }) { telegramBot(cli, base, token) }) {
        is BotCheck.Bot -> {
            cli.ui.good(cli.t("bot.belongs", "bot" to described(cli, checked)))
            checked to token
        }

        is BotCheck.Refused -> {
            cli.fail(cli.texts(checked.reason))
            null
        }
    }
}

/** The name of the Telegram instance of [config] that is the bot [token], null when none is. */
internal fun configuredBot(cli: Cli, config: JsonObject, token: String): String? {
    val environment = try {
        withSecrets(cli.environment, cli.secrets)
    } catch (e: IOException) {
        cli.environment
    }
    return config.objectAt(TELEGRAM_INSTANCES).entries.firstOrNull { (_, value) ->
        val written = (value as? JsonObject)?.get("botToken").text ?: return@firstOrNull false
        val variable = REFERENCE.matchEntire(written)?.groupValues?.get(1)
        (if (variable != null) environment[variable] else written) == token
    }?.key
}

private val REFERENCE = Regex("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}")

// MCP servers.

internal fun mcpAdd(cli: Cli, name: String, url: String?, command: List<String>, restart: Boolean): Int {
    val config = cli.rawConfig() ?: return ExitCode.CONFIG
    if (config.at("$MCP_SERVERS.$name") != null) return cli.fail(cli.t("mcp.exists", "server" to name))
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
    if (config.at("$MCP_SERVERS.$name") == null) return cli.fail(cli.t("mcp.unknown", "server" to name))
    return changeConfig(cli, listOf(ConfigChangeView("$MCP_SERVERS.$name", remove = true)), restart)
}

internal fun mcpList(cli: Cli, json: Boolean): Int {
    val reply = try {
        cli.ask(ControlRequest.McpServers)
    } catch (e: IOException) {
        return cli.fail(cli.t("unreachable", "reason" to (e.message ?: e)))
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

        else -> return cli.fail(cli.t("unexpected_answer"))
    }
    if (json) {
        cli.printJson(JsonArray(servers))
        return ExitCode.OK
    }
    if (servers.isEmpty()) return cli.done(cli.t("mcp.none"))
    for (server in servers) {
        val tools = (server["tools"] as? JsonPrimitive)?.content?.let { cli.t("mcp.tools", "count" to it) }.orEmpty()
        val error = server["lastError"].text?.let { cli.t("mcp.last_error", "error" to it) }.orEmpty()
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
        else -> return cli.fail(cli.t("unexpected_answer"))
    }
    cli.out.println(cli.t("mcp.open", "server" to server))
    cli.out.println(started.url)
    if (!cli.machine.interactive) {
        return cli.done(
            if (started.loopback) cli.t("mcp.loopback") else cli.t("mcp.then_address", "server" to server),
        )
    }
    val pasted = cli.machine.ask(cli.t("mcp.paste"))?.trim().orEmpty()
    if (pasted.isNotEmpty()) return mcpAnswered(cli, server, ControlRequest.McpComplete(server, pasted))
    val state = (mcpAsk(cli, ControlRequest.McpServers) as? ControlReply.McpServers)?.servers
        ?.firstOrNull { it.name == server }?.state
    return if (state == "needs_login") {
        cli.fail(cli.t("mcp.still_waiting", "server" to server))
    } else {
        cli.done(cli.t("mcp.state", "server" to server, "state" to state))
    }
}

internal fun mcpLogout(cli: Cli, server: String): Int = mcpAnswered(cli, server, ControlRequest.McpLogout(server))

private fun mcpAnswered(cli: Cli, server: String, request: ControlRequest): Int =
    when (val reply = mcpAsk(cli, request) ?: return ExitCode.UNAVAILABLE) {
        is ControlReply.McpLoggedIn -> cli.done(cli.t("mcp.logged_in", "server" to server))

        is ControlReply.McpLoggedOut ->
            cli.done(cli.t(if (reply.had) "mcp.logged_out" else "mcp.no_login", "server" to server))

        is ControlReply.Failure -> cli.fail(reply.message)

        else -> cli.fail(cli.t("unexpected_answer"))
    }

/** The running app's answer to [request], null after printing that none runs or why it failed. */
private fun mcpAsk(cli: Cli, request: ControlRequest): ControlReply? = try {
    cli.ask(request) ?: run {
        cli.fail(cli.t("mcp.not_running"), ExitCode.UNAVAILABLE)
        null
    }
} catch (e: IOException) {
    cli.fail(cli.t("unreachable", "reason" to (e.message ?: e)))
    null
}
