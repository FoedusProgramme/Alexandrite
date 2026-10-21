package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.app.tui.Choice
import org.unitedsoftware.alexandrite.internal.time.zoneOrNull
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import java.io.IOException
import java.nio.file.Files

private const val HEARTBEATS = "automations.heartbeat.agents"

/** The answer that removes an optional value. */
private const val CLEAR = "-"

private val BACK: () -> Unit = {}

/** The subcommands of `config`, joined for a message. */
internal fun configSubcommands(): String =
    COMMANDS.filter { it.words.size == 2 && it.words.first() == "config" }.joinToString { it.words[1] }

/** Opens the menu that changes the config on the terminal, or prints the usage of `config` where none is drawn. */
internal fun configMenu(cli: Cli): Int = cli.onTerminal {
    if (cli.ui.rich) ConfigMenu(cli).run() else configUsage(cli)
}

private fun configUsage(cli: Cli): Int {
    val needs = cli.t("usage.needs_subcommand", "command" to "config", "subcommands" to configSubcommands())
    cli.err.println("alexandrite: $needs")
    cli.err.println()
    cli.err.println(usage(COMMANDS.first { it.words == listOf("config") }, cli.texts))
    return ExitCode.USAGE
}

/** An endpoint of the config: the [provider] it belongs to, its [name] and how it is configured. */
private class Endpoint(val provider: String, val name: String, val config: JsonObject) {
    val kind: ProviderKind? get() = providerKind(provider)

    val base: String? get() = config["baseUrl"].text ?: kind?.defaultUrl

    val path: String get() = "providers.$provider.endpoints.$name"
}

private fun endpoints(config: JsonObject): List<Endpoint> = config.objectAt("providers").flatMap { (provider, value) ->
    (value as? JsonObject)?.objectAt("endpoints")?.mapNotNull { (name, endpoint) ->
        (endpoint as? JsonObject)?.let { Endpoint(provider, name, it) }
    }.orEmpty()
}

/** The channel instances of the config, such as `telegram:main`. */
private fun instances(config: JsonObject): List<String> = config.objectAt("channels").flatMap { (type, value) ->
    (value as? JsonObject)?.objectAt("instances")?.keys?.map { "$type:$it" }.orEmpty()
}

private fun userIds(element: JsonElement?): List<Long> =
    (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()

/** The user ids [text] lists, null when it lists none or something else. */
private fun parsedIds(text: String): List<Long>? =
    text.split(',', ' ').filter(String::isNotEmpty).map { it.toLongOrNull()?.takeIf { id -> id > 0 } ?: return null }
        .takeIf { it.isNotEmpty() }

private fun languageOf(text: String): String? = try {
    LanguageTag.of(text).value
} catch (e: IllegalArgumentException) {
    null
}

/** One run of the menu, and the keys its changes left for the running app to apply once it starts again. */
private class ConfigMenu(private val cli: Cli) {
    private val ui: Ui get() = cli.ui

    private val pending = linkedSetOf<String>()

    private val report: Report = { written ->
        reportChange(written)
        ExitCode.OK
    }

    private fun t(key: String, vararg args: Pair<String, Any?>): String = cli.t(key, *args)

    fun run(): Int {
        ui.intro(t("menu.title"))
        cli.rawConfig() ?: return ExitCode.CONFIG
        ui.say(t("menu.welcome", "file" to cli.locations.configFile))
        screen(t("menu.main"), t("menu.done")) { main(it) }
        var code = ExitCode.OK
        if (pending.isNotEmpty()) {
            val now = installedService(cli) != null &&
                back { ui.confirm(t("menu.restart_now", "keys" to pending.joinToString()), true) } == true
            if (now) code = restart(cli) else ui.warn(t("change.after_restart"))
        }
        ui.outro(t("menu.bye"))
        return code
    }

    /**
     * Offers what [choices] makes of the config under [title] and runs the action chosen, until the person picks
     * [last] or goes back with Esc, or [choices] gives null.
     */
    private fun screen(
        title: String,
        last: String = t("menu.back"),
        choices: (JsonObject) -> List<Choice<() -> Unit>>?,
    ) {
        while (true) {
            val shown = choices(cli.rawConfig() ?: return) ?: return
            val action = back { ui.choose(title, shown + Choice(last, BACK)) { it } } ?: return
            if (action === BACK) return
            back(action)
        }
    }

    /** What [block] returns, null when the person goes back with Esc. */
    private fun <T> back(block: () -> T): T? = try {
        block()
    } catch (e: CancelledException) {
        if (e.interrupted) throw e
        null
    }

    private fun item(label: String, hint: String? = null, action: () -> Unit) = Choice(label, action, hint)

    private fun change(vararg changes: ConfigChangeView) {
        changeConfig(cli, changes.toList(), report)
    }

    private fun reportChange(written: Written) {
        val changes = written.changes
        if (changes.changed.isEmpty()) return ui.say(t("change.none"))
        ui.good(t("change.done", "keys" to changes.changed.joinToString(), "file" to written.file))
        if (!written.running) return ui.say(t("change.at_start"))
        val live = changes.changed - changes.restart.toSet()
        if (live.isNotEmpty()) ui.say(t("change.live", "keys" to live.joinToString()))
        if (changes.restart.isEmpty()) return
        ui.warn(t("menu.needs_restart", "keys" to changes.restart.joinToString()))
        pending += changes.restart
    }

    private fun named(names: List<String>): String = names.joinToString().ifEmpty { t("menu.none") }

    private fun required(answer: String): String? = if (answer.isBlank()) t("menu.required") else null

    private fun web(answer: String): String? = if (answer.startsWith("http://") ||
        answer.startsWith("https://")
    ) {
        null
    } else {
        t("init.model.no_url", "answer" to answer)
    }

    /** Why [answer] cannot name a new entry beside [taken], the [what] text naming what it is; null when it can. */
    private fun newId(answer: String, taken: Collection<String>, what: String): String? = when {
        !PluginIds.PATTERN.matches(answer) -> t("usage.no_id", "id" to answer, "what" to Text(what))
        answer in taken -> t("menu.taken", "name" to answer)
        else -> null
    }

    private fun main(config: JsonObject): List<Choice<() -> Unit>> {
        val app = config.objectAt("app")
        return listOf(
            item(t("menu.agents"), named(agents(config).map { it.id }), ::agentsScreen),
            item(t("menu.endpoints"), named(endpoints(config).map { it.name }), ::endpointsScreen),
            item(t("menu.channels"), named(instances(config)), ::channelsScreen),
            item(t("menu.mcp"), named(config.objectAt(MCP_SERVERS).keys.toList()), ::mcpScreen),
            item(t("menu.heartbeats"), named(config.objectAt(HEARTBEATS).keys.toList()), ::heartbeatsScreen),
            item(
                t("menu.language"),
                "${app["language"].text ?: "en"} · ${app["zone"].text ?: cli.zone.id}",
                ::language,
            ),
            item(t("menu.other"), action = ::otherKey),
            item(t("menu.editor")) { configEdit(cli, report) },
            item(t("menu.check")) { configCheck(cli, json = false) },
        )
    }

    // Agents.

    private fun agentHint(agent: AgentEntry): String = listOfNotNull(
        agent.name,
        agent.models.firstOrNull() ?: t("menu.agent.no_model"),
        agent.channels.keys.joinToString().ifEmpty { t("agent.no_channel") },
    ).joinToString(" · ")

    private fun agentsScreen() = screen(t("menu.agents")) { config ->
        agents(config).map { agent -> item(agent.id, agentHint(agent)) { agentScreen(agent.id) } } +
            item(t("menu.agent.add"), action = ::addAgent)
    }

    private fun agentScreen(id: String) = screen(t("menu.agent.title", "agent" to id)) { config ->
        val agent = agents(config).firstOrNull { it.id == id } ?: return@screen null
        val mode = agent.config.at("permission.mode").text
        listOf(
            item(t("menu.agent.name"), agent.name ?: t("menu.unset")) { rename(agent) },
            item(t("menu.agent.model"), agent.models.joinToString().ifEmpty { t("menu.unset") }) { setModel(agent) },
            item(t("menu.agent.mode"), mode ?: t("menu.default")) { setMode(agent) },
            item(t("menu.agent.channels"), agent.channels.keys.joinToString().ifEmpty { t("agent.no_channel") }) {
                agentChannels(id)
            },
            item(t("menu.agent.remove")) {
                if (ui.confirm(t("menu.agent.remove_confirm", "agent" to id), false)) agentRemove(cli, id, report)
            },
        )
    }

    private fun rename(agent: AgentEntry) {
        val name = ui.ask(t("menu.agent.name_prompt"), agent.name, ::required)?.trim() ?: return
        change(ConfigChangeView("$AGENTS.${agent.id}.name", JsonPrimitive(name)))
    }

    private fun setModel(agent: AgentEntry) {
        val model = modelOf(cli.rawConfig() ?: return) ?: return
        change(*defaultModels(agent.id, agent.config, listOf(model)).toTypedArray())
    }

    private fun setMode(agent: AgentEntry) {
        val current = agent.config.at("permission.mode").text
        val modes = (PermissionMode.entries - PermissionMode.BYPASS).map { it.id }
        val choices = modes.map { Choice(it, it, hint = t("menu.current").takeIf { _ -> it == current }) } +
            Choice(t("menu.default"), "", hint = t("menu.current").takeIf { current == null })
        val mode = ui.choose(t("menu.agent.mode"), choices) { it } ?: return
        val path = "$AGENTS.${agent.id}.permission.mode"
        change(
            if (mode.isEmpty()) ConfigChangeView(path, remove = true) else ConfigChangeView(path, JsonPrimitive(mode)),
        )
    }

    private fun agentChannels(id: String) = screen(t("menu.agent.channels_title", "agent" to id)) { config ->
        val agent = agents(config).firstOrNull { it.id == id } ?: return@screen null
        instances(config).map { instance ->
            val serves = instance in agent.channels
            item(instance, t(if (serves) "menu.channel.serves" else "menu.channel.not_served")) {
                val path = "$AGENTS.$id.channels.$instance"
                change(
                    if (serves) {
                        ConfigChangeView(path, remove = true)
                    } else {
                        ConfigChangeView(path, JsonObject(emptyMap()))
                    },
                )
            }
        }
    }

    private fun addAgent() {
        val config = cli.rawConfig() ?: return
        val taken = agents(config).map { it.id }
        val id = ui.ask(t("menu.agent.id"), null) { newId(it, taken, "what.agent_id") } ?: return
        val description = ui.ask(t("menu.agent.description"), null, ::required)?.trim() ?: return
        val model = if (endpoints(config).isEmpty()) null else modelOf(config) ?: return
        agentAdd(cli, id, null, description, model, emptyList(), false, report)
    }

    /** The agent the person chooses, the only one without asking; null after saying there is none. */
    private fun agentFor(config: JsonObject): AgentEntry? {
        val agents = agents(config)
        if (agents.isEmpty()) {
            ui.warn(t("menu.no_agents"))
            return null
        }
        return agents.singleOrNull()
            ?: ui.choose(t("menu.agent.which"), agents.map { Choice(it.id, it, hint = agentHint(it)) }) { it }
    }

    // Models and endpoints.

    /** `<endpoint>/<model>` of the model the person chooses from a configured endpoint, null when there is none. */
    private fun modelOf(config: JsonObject): String? {
        val endpoints = endpoints(config)
        if (endpoints.isEmpty()) {
            ui.warn(t("menu.model.no_endpoints"))
            return null
        }
        val endpoint = endpoints.singleOrNull()
            ?: ui.choose(
                t("menu.model.endpoint"),
                endpoints.map {
                    Choice(it.name, it, hint = endpointHint(it))
                },
            ) { it }
            ?: return null
        val listed = listed(listing(endpoint.kind, endpoint.base, keyOf(endpoint)))
        val model = chosenModels(listed, single = true)?.firstOrNull() ?: return null
        return "${endpoint.name}/$model"
    }

    /** What the endpoint of [kind] at [base] lists for [key], null when it cannot be asked. */
    private fun listing(kind: ProviderKind?, base: String?, key: String?): Listing? {
        if (kind == null || base == null) return null
        return ui.busy({ t("init.model.checking", "base" to base) }) { listModels(cli, kind, base, key) }
    }

    /** The models of [listing], null after saying why there are none. */
    private fun listed(listing: Listing?): List<String>? = when (listing) {
        is Listing.Models -> listing.ids
        is Listing.Refused -> null.also { ui.warn(cli.texts(listing.reason)) }
        is Listing.Unchecked -> null.also { ui.warn(cli.texts(listing.reason)) }
        null -> null
    }

    private fun keyOf(endpoint: Endpoint): String? {
        val written = endpoint.config["apiKey"].text ?: return null
        val variable = referencedVariable(written) ?: return written
        return try {
            withSecrets(cli.environment, cli.secrets)[variable]
        } catch (e: IOException) {
            cli.environment[variable]
        }
    }

    /** The models the person picks from [listed], or types when it holds none. */
    private fun chosenModels(listed: List<String>?, single: Boolean): List<String>? {
        if (listed.isNullOrEmpty()) {
            val prompt = if (listed == null) "init.model.type_unlisted" else "init.model.type"
            return ui.ask(t(prompt), null, ::required)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                ?.distinct()
        }
        return if (single) {
            ui.pick(t("menu.model.pick"), listed, t("menu.model.pick_hint"))
        } else {
            ui.pick(t("init.model.pick_title"), listed, t("init.model.pick_hint"))
        }
    }

    private fun providerTitle(provider: String): String =
        providerKind(provider)?.let { t("provider.${it.id}") } ?: provider

    private fun endpointHint(endpoint: Endpoint): String =
        listOfNotNull(providerTitle(endpoint.provider), endpoint.base).joinToString(" · ")

    private fun endpointsScreen() = screen(t("menu.endpoints")) { config ->
        endpoints(config).map { item(it.name, endpointHint(it)) { endpointScreen(it.path) } } +
            item(t("menu.endpoint.add"), action = ::addEndpoint)
    }

    private fun endpointScreen(path: String) =
        screen(t("menu.endpoint.title", "endpoint" to path.substringAfterLast('.'))) { config ->
            val endpoint = endpoints(config).firstOrNull { it.path == path } ?: return@screen null
            val used = agents(config).flatMap { agent ->
                agent.models.filter {
                    it.startsWith("${endpoint.name}/")
                }.map { "${agent.id}: ${it.substringAfter('/')}" }
            }
            val key = endpoint.config["apiKey"].text?.let { referencedVariable(it) ?: t("menu.endpoint.key_written") }
            listOf(
                item(t("menu.endpoint.base"), endpoint.base ?: t("menu.unset")) { setBase(endpoint) },
                item(t("menu.endpoint.key"), key ?: t("menu.endpoint.no_key")) { setKey(endpoint) },
                item(t("menu.endpoint.models"), named(used)) { setModels(endpoint) },
                item(t("menu.endpoint.remove")) {
                    if (ui.confirm(t("menu.endpoint.remove_confirm", "endpoint" to endpoint.name), false)) {
                        change(ConfigChangeView(endpoint.path, remove = true))
                    }
                },
            )
        }

    private fun setBase(endpoint: Endpoint) {
        val base = ui.ask(t("init.model.base_url"), endpoint.base, ::web)?.trim()?.trimEnd('/') ?: return
        val path = "${endpoint.path}.baseUrl"
        change(
            if (base == endpoint.kind?.defaultUrl) {
                ConfigChangeView(path, remove = true)
            } else {
                ConfigChangeView(path, JsonPrimitive(base))
            },
        )
    }

    private fun keyPrompt(provider: String, required: Boolean): String = t(
        if (required) "init.model.key_title" else "init.model.key_optional_title",
        "provider" to providerTitle(provider),
    )

    private fun variableOf(provider: String, endpoint: String): String =
        providerKind(provider)?.takeIf { it.endpoint == endpoint }?.keyVariable ?: keyVariable(endpoint)

    private fun setKey(endpoint: Endpoint) {
        val required = endpoint.kind?.keyRequired != false
        val key = ui.secret(keyPrompt(endpoint.provider, required))?.trim() ?: return
        val path = "${endpoint.path}.apiKey"
        if (key.isEmpty()) {
            if (required) return ui.warn(t("init.model.key_needed"))
            return change(ConfigChangeView(path, remove = true))
        }
        val held = endpoint.config["apiKey"].text?.let(::referencedVariable)
        val variable = held ?: variableOf(endpoint.provider, endpoint.name)
        if (held ==
            null
        ) {
            return saving(
                mapOf(variable to key),
                listOf(ConfigChangeView(path, JsonPrimitive(envReference(variable)))),
            )
        }
        if (saved(mapOf(variable to key)) == null) return
        ui.good(t("init.secrets", "names" to variable, "file" to cli.secrets))
        if (cli.status() == null) return ui.say(t("secret.at_start"))
        ui.warn(t("menu.needs_restart", "keys" to path))
        pending += path
    }

    private fun setModels(endpoint: Endpoint) {
        val agent = agentFor(cli.rawConfig() ?: return) ?: return
        val listed = listed(listing(endpoint.kind, endpoint.base, keyOf(endpoint)))
        val models = chosenModels(listed, single = false) ?: return
        change(*defaultModels(agent.id, agent.config, models.map { "${endpoint.name}/$it" }).toTypedArray())
    }

    private fun addEndpoint() {
        val kind = ui.choose(
            t("init.model.provider_title"),
            PROVIDER_KINDS.map { Choice(t("provider.${it.id}"), it, key = it.id) },
        ) { it } ?: return
        val config = cli.rawConfig() ?: return
        val taken = endpoints(config).map { it.name }
        val name = ui.ask(t("menu.endpoint.name"), kind.endpoint.takeIf { it !in taken }) {
            newId(it, taken, "what.endpoint")
        } ?: return
        val base = ui.ask(t("init.model.base_url"), kind.defaultUrl, ::web)?.trim()?.trimEnd('/') ?: return
        val key = ui.secret(keyPrompt(kind.id, kind.keyRequired))?.trim() ?: return
        if (key.isEmpty() && kind.keyRequired) return ui.warn(t("init.model.key_needed"))
        val listing = listing(kind, base, key.ifEmpty { null })
        if (listing is Listing.Refused) return ui.error(cli.texts(listing.reason))
        if (listing is Listing.Models) {
            val text = if (key.isEmpty()) "init.model.listed_keyless" else "init.model.listed"
            ui.good(t(text, "base" to base, "count" to listing.ids.size))
        }
        val models = chosenModels(listed(listing), single = false) ?: return
        val agent = if (agents(config).isEmpty()) null else agentFor(config) ?: return
        val root = "providers.${kind.id}.endpoints.$name"
        val variable = variableOf(kind.id, name)
        val changes = mutableListOf<ConfigChangeView>()
        if (base != kind.defaultUrl) changes += ConfigChangeView("$root.baseUrl", JsonPrimitive(base))
        if (key.isNotEmpty()) changes += ConfigChangeView("$root.apiKey", JsonPrimitive(envReference(variable)))
        if (changes.isEmpty()) changes += ConfigChangeView(root, JsonObject(emptyMap()))
        if (agent ==
            null
        ) {
            changes += ConfigChangeView("$AGENTS.main.description", JsonPrimitive(t("menu.agent.created")))
        }
        changes += defaultModels(agent?.id ?: "main", agent?.config, models.map { "$name/$it" })
        saving(if (key.isEmpty()) emptyMap() else mapOf(variable to key), changes)
    }

    /** The secrets file as it was before [secrets] were saved in it, null after saying why they could not be. */
    private fun saved(secrets: Map<String, String>): Map<String, String>? {
        val file = cli.secrets
        return try {
            readSecrets(file).also { writeSecrets(file, it + secrets) }
        } catch (e: IOException) {
            cli.fail(t("secret.unchangeable", "file" to file, "reason" to (e.message ?: e)))
            null
        }
    }

    /** Saves [secrets] and makes [changes], and takes the secrets back when the changes are not made. */
    private fun saving(secrets: Map<String, String>, changes: List<ConfigChangeView>) {
        if (secrets.isEmpty()) return change(*changes.toTypedArray())
        val existed = Files.exists(cli.secrets)
        val before = saved(secrets) ?: return
        if (changeConfig(cli, changes, report) == ExitCode.OK) {
            return ui.good(t("init.secrets", "names" to secrets.keys.joinToString(), "file" to cli.secrets))
        }
        try {
            if (existed) writeSecrets(cli.secrets, before) else Files.deleteIfExists(cli.secrets)
        } catch (e: IOException) {
            cli.fail(t("secret.unwritable", "file" to cli.secrets, "reason" to (e.message ?: e)))
        }
    }

    // Channels.

    private fun channelsScreen() = screen(t("menu.channels")) { config ->
        config.objectAt(TELEGRAM_INSTANCES).keys.map { name ->
            val admins = userIds(config.objectAt("$TELEGRAM_INSTANCES.$name")["adminUserIds"]).map(Long::toString)
            val served = agents(config).filter { "telegram:$name" in it.channels }.map { it.id }
            val hint = t("menu.channel.hint", "admins" to named(admins), "agents" to named(served))
            item("telegram:$name", hint) { telegramScreen(name) }
        } + item(t("menu.channel.add"), action = ::addChannel)
    }

    private fun telegramScreen(name: String) =
        screen(t("menu.channel.title", "instance" to "telegram:$name")) { config ->
            val instance = config.at("$TELEGRAM_INSTANCES.$name") as? JsonObject ?: return@screen null
            val admins = userIds(instance["adminUserIds"]).map(Long::toString)
            val trusted = userIds(instance["trustedUserIds"]).map(Long::toString)
            listOf(
                item(t("menu.channel.admins"), named(admins)) { setUsers(name, "adminUserIds", admins) },
                item(t("menu.channel.trusted"), named(trusted)) { setUsers(name, "trustedUserIds", trusted) },
                item(t("menu.channel.remove")) { removeChannel(name, config) },
            )
        }

    private fun setUsers(name: String, key: String, current: List<String>) {
        val answer = ui.ask(t("menu.channel.users"), current.joinToString().ifEmpty { null }) {
            if (it.trim() == CLEAR || parsedIds(it) != null) null else t("menu.channel.no_ids", "answer" to it)
        } ?: return
        val path = "$TELEGRAM_INSTANCES.$name.$key"
        val ids = parsedIds(answer)
        change(
            if (ids == null) {
                ConfigChangeView(path, remove = true)
            } else {
                ConfigChangeView(path, JsonArray(ids.map(::JsonPrimitive)))
            },
        )
    }

    private fun removeChannel(name: String, config: JsonObject) {
        val instance = "telegram:$name"
        if (!ui.confirm(t("menu.channel.remove_confirm", "instance" to instance), false)) return
        val attachments = agents(config).filter { instance in it.channels }
            .map { ConfigChangeView("$AGENTS.${it.id}.channels.$instance", remove = true) }
        change(*(attachments + ConfigChangeView("$TELEGRAM_INSTANCES.$name", remove = true)).toTypedArray())
    }

    private fun addChannel() {
        val config = cli.rawConfig() ?: return
        val taken = config.objectAt(TELEGRAM_INSTANCES).keys
        val name = ui.ask(t("menu.channel.name"), "main".takeIf { it !in taken }) {
            newId(it, taken, "what.instance_name")
        } ?: return
        val agent = if (agents(config).size > 1) agentFor(config)?.id ?: return else null
        addTelegram(cli, name, emptyList(), agent, null, null, report)
    }

    // MCP servers.

    private fun mcpHint(value: JsonElement): String {
        val server = value as? JsonObject ?: return ""
        val args = (server["args"] as? JsonArray)?.mapNotNull { it.text }.orEmpty()
        val target = server["url"].text ?: (listOfNotNull(server["command"].text) + args).joinToString(" ")
        return if (server["enabled"] == JsonPrimitive(false)) t("menu.mcp.off", "target" to target) else target
    }

    private fun mcpScreen() = screen(t("menu.mcp")) { config ->
        config.objectAt(MCP_SERVERS).map { (name, value) ->
            item(name, mcpHint(value)) {
                if (ui.confirm(t("menu.mcp.remove_confirm", "server" to name), false)) mcpRemove(cli, name, report)
            }
        } + item(t("menu.mcp.add"), action = ::addMcp)
    }

    private fun addMcp() {
        val taken = (cli.rawConfig() ?: return).objectAt(MCP_SERVERS).keys
        val name = ui.ask(t("menu.mcp.name"), null) {
            when {
                it.isBlank() || '.' in it -> t("menu.mcp.no_name", "answer" to it)
                it in taken -> t("menu.taken", "name" to it)
                else -> null
            }
        }?.trim() ?: return
        val http = ui.choose(
            t("menu.mcp.transport"),
            listOf(Choice(t("menu.mcp.http"), true), Choice(t("menu.mcp.stdio"), false)),
        ) { it } ?: return
        if (http) {
            val url = ui.ask(t("menu.mcp.url"), null, ::web)?.trim() ?: return
            mcpAdd(cli, name, url, emptyList(), report)
        } else {
            val command = ui.ask(t("menu.mcp.command"), null, ::required)?.trim()?.split(Regex("\\s+")) ?: return
            mcpAdd(cli, name, null, command, report)
        }
    }

    // Heartbeats.

    private fun heartbeatHint(beat: JsonObject?): String {
        beat ?: return t("menu.heartbeat.off")
        val every = t("menu.heartbeat.every_hint", "every" to beat["every"].text)
        return listOfNotNull(every, beat["activeHours"].text, beat["zone"].text).joinToString(" · ")
    }

    private fun heartbeatsScreen() = screen(t("menu.heartbeats")) { config ->
        agents(config).map { agent ->
            val beat = config.at("$HEARTBEATS.${agent.id}") as? JsonObject
            item(agent.id, heartbeatHint(beat)) { heartbeatScreen(agent.id) }
        }
    }

    private fun heartbeatScreen(id: String) = screen(t("menu.heartbeat.title", "agent" to id)) { config ->
        val path = "$HEARTBEATS.$id"
        val beat = config.at(path) as? JsonObject
            ?: return@screen listOf(item(t("menu.heartbeat.turn_on")) { turnOn(path) })
        val every = beat["every"].text
        val hours = beat["activeHours"].text
        val zone = beat["zone"].text
        val prompt = beat["prompt"].text
        listOf(
            item(t("menu.heartbeat.every"), every) { field("$path.every", t("menu.heartbeat.every_prompt"), every) },
            item(t("menu.heartbeat.hours"), hours ?: t("menu.heartbeat.all_day")) {
                field("$path.activeHours", t("menu.heartbeat.hours_prompt"), hours, optional = true)
            },
            item(t("menu.heartbeat.zone"), zone ?: t("menu.heartbeat.host_zone")) {
                field("$path.zone", t("menu.heartbeat.zone_prompt"), zone, optional = true)
            },
            item(t("menu.heartbeat.prompt"), prompt ?: t("menu.heartbeat.default_prompt")) {
                field("$path.prompt", t("menu.heartbeat.prompt_prompt"), prompt, optional = true)
            },
            item(t("menu.heartbeat.turn_off")) { change(ConfigChangeView(path, remove = true)) },
        )
    }

    private fun turnOn(path: String) {
        val every = ui.ask(t("menu.heartbeat.every_prompt"), "30m", ::required)?.trim() ?: return
        change(ConfigChangeView(path, buildJsonObject { put("every", every) }))
    }

    /** Sets the string at [path] to what the person answers to [prompt], or removes it when [optional]. */
    private fun field(path: String, prompt: String, current: String?, optional: Boolean = false) {
        val answer = ui.ask(prompt, current, ::required)?.trim() ?: return
        change(
            if (optional && answer == CLEAR) {
                ConfigChangeView(path, remove = true)
            } else {
                ConfigChangeView(path, JsonPrimitive(answer))
            },
        )
    }

    // Language, zone and other keys.

    private fun language() {
        val app = (cli.rawConfig() ?: return).objectAt("app")
        val language = ui.ask(t("init.language.prompt"), app["language"].text ?: "en") {
            if (languageOf(it) == null) t("init.invalid", "answer" to it) else null
        }?.let(::languageOf) ?: return
        val zone = ui.ask(t("init.zone.prompt"), app["zone"].text ?: cli.zone.id) {
            if (zoneOrNull(it) == null) t("init.invalid", "answer" to it) else null
        }?.let { zoneOrNull(it)?.id } ?: return
        change(
            ConfigChangeView("app.language", JsonPrimitive(language)),
            ConfigChangeView("app.zone", JsonPrimitive(zone)),
        )
    }

    private fun otherKey() {
        val key = ui.ask(t("menu.other.key"), null) {
            keyProblem(it.trim())?.let { reason -> t("usage.no_key", "key" to it, "reason" to reason) }
        }?.trim() ?: return
        val current = (cli.rawConfig() ?: return).at(key)
        ui.say(
            if (current == null) {
                t("menu.other.unset", "key" to key)
            } else {
                t("menu.other.current", "key" to key, "value" to current)
            },
        )
        val text = ui.ask(t("menu.other.value"), current?.let { it.text ?: "$it" }, ::required) ?: return
        change(ConfigChangeView(key, valueOf(text.trim())))
    }
}
