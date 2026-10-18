package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress

private const val JSON = "--json"
private const val RESTART = "--restart"
private const val AGENT = "--agent"
private const val EXAMPLE_CHAT = "telegram:work:123456789"

private const val RUN = "Run"
private const val SETUP = "Setup"
private const val CONFIGURATION = "Config"
private const val SECRETS = "Secrets"
private const val SHORTCUTS = "Shortcuts"
private const val CHATS = "Chats, answered by the Alexandrite that runs with the same data directory"

private const val CHANGES = "The running Alexandrite checks the change as a start would, writes it and says what " +
    "needs a restart; with none running, the command checks and writes the file itself. With --restart the " +
    "command restarts the service once the change is written."

private const val JSON_OUTPUT = "With --json it prints JSON for scripts."

private fun noArguments(given: Given, name: String) = given.atMost(0, "$name takes no argument.")

/** The commands of `alexandrite`, in the order of the help. */
internal val COMMANDS: List<CliCommand> = listOf(
    CliCommand(listOf("run"), "", RUN, "run Alexandrite in the foreground until SIGTERM or SIGINT") {
        noArguments(it, "run")
        CliAction.Foreground
    },
    CliCommand(listOf("start"), "", RUN, "start the background service and wait until Alexandrite is ready") {
        noArguments(it, "start")
        CliAction.Act(::start)
    },
    CliCommand(
        listOf("stop"),
        "",
        RUN,
        "stop the background service, or the Alexandrite that runs in the foreground",
    ) {
        noArguments(it, "stop")
        CliAction.Act(::stop)
    },
    CliCommand(listOf("restart"), "", RUN, "restart the background service and wait until Alexandrite is ready") {
        noArguments(it, "restart")
        CliAction.Act(::restart)
    },
    CliCommand(
        listOf("status"),
        "[$JSON]",
        RUN,
        "show whether Alexandrite runs, its channel instances and the service",
        "It exits 0 only when a running Alexandrite answers on its control socket, and 69 otherwise. $JSON_OUTPUT",
        flags = setOf(JSON),
    ) { given ->
        noArguments(given, "status")
        CliAction.Act { status(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("logs"),
        "[-f] [-n <lines>]",
        RUN,
        "print the service's log, and follow it with -f",
        "Prints the last 200 lines unless -n names another number.",
        flags = setOf("-f", "--follow"),
        options = setOf("-n", "--lines"),
    ) { given ->
        noArguments(given, "logs")
        val text = given.option("-n") ?: given.option("--lines") ?: "200"
        val lines = text.toIntOrNull()?.takeIf { it > 0 } ?: throw UsageException("-n needs a positive number.")
        CliAction.Act { logs(it, lines, given.flag("-f") || given.flag("--follow")) }
    },
    CliCommand(
        listOf("doctor"),
        "[$JSON]",
        SETUP,
        "check Java, the config, the endpoints and Telegram, the secrets, the disk and the service",
        "It exits 1 when a check fails. $JSON_OUTPUT",
        flags = setOf(JSON),
    ) { given ->
        noArguments(given, "doctor")
        CliAction.Act { doctor(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("update"),
        "[--version <release>]",
        SETUP,
        "update to the latest release, or to <release>",
        "Runs the install script in its update mode, which checks the new release against the config file " +
            "before it switches to it and restarts the service.",
        options = setOf("--version"),
    ) { given ->
        noArguments(given, "update")
        CliAction.Act { update(it, given.option("--version")) }
    },
    CliCommand(
        listOf("uninstall"),
        "[--keep-data] [--yes]",
        SETUP,
        "remove the service, the program and, unless --keep-data, the config, secrets and data",
        "Asks before it removes anything, unless --yes.",
        flags = setOf("--keep-data", "--yes"),
    ) { given ->
        noArguments(given, "uninstall")
        CliAction.Act { uninstall(it, given.flag("--keep-data"), given.flag("--yes")) }
    },
    CliCommand(
        listOf("config", "get"),
        "[<key>] [$JSON]",
        CONFIGURATION,
        "print the value at <key>, or the whole config file",
        "A key is the config's path, such as agent.agents.main.model; values print as the file writes them. " +
            "With --json a string prints as JSON as well.",
        flags = setOf(JSON),
    ) { given ->
        given.atMost(1, "config get takes one key.")
        val key = given.operands.firstOrNull()?.let(::requireKey)
        CliAction.Act { configGet(it, key, given.flag(JSON)) }
    },
    CliCommand(
        listOf("config", "set"),
        "<key> <value> [$RESTART]",
        CONFIGURATION,
        "set the value at <key>",
        "The value is JSON, or a string when it does not read as JSON. $CHANGES",
        flags = setOf(RESTART),
    ) { given ->
        if (given.operands.size < 2) throw UsageException("config set needs a key and a value.")
        val key = requireKey(given.operands.first())
        val value = valueOf(given.operands.drop(1).joinToString(" "))
        CliAction.Act { changeConfig(it, listOf(ConfigChangeView(key, value)), given.flag(RESTART)) }
    },
    CliCommand(
        listOf("config", "unset"),
        "<key> [$RESTART]",
        CONFIGURATION,
        "remove the value at <key>",
        CHANGES,
        flags = setOf(RESTART),
    ) { given ->
        val key = requireKey(given.single("config unset needs one key."))
        CliAction.Act { changeConfig(it, listOf(ConfigChangeView(key, remove = true)), given.flag(RESTART)) }
    },
    CliCommand(
        listOf("config", "edit"),
        "[$RESTART]",
        CONFIGURATION,
        "edit a copy of the config file in \$VISUAL or \$EDITOR and write it once it passes",
        "A copy that does not pass is offered for another edit, and the file stays as it was. $CHANGES",
        flags = setOf(RESTART),
    ) { given ->
        noArguments(given, "config edit")
        CliAction.Act { configEdit(it, given.flag(RESTART)) }
    },
    CliCommand(
        listOf("config", "check"),
        "[$JSON]",
        CONFIGURATION,
        "check the config file as a start would",
        "It exits 78 when the file does not pass. $JSON_OUTPUT",
        flags = setOf(JSON),
    ) { given ->
        noArguments(given, "config check")
        CliAction.Act { configCheck(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("config", "path"),
        "[$JSON]",
        CONFIGURATION,
        "print where the config file is",
        "With --json it prints the secrets file, the data and the cache directories as well.",
        flags = setOf(JSON),
    ) { given ->
        noArguments(given, "config path")
        CliAction.Act { configPath(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("secret", "set"),
        "<NAME>",
        SECRETS,
        "save a secret, typed without echo, in $SECRETS_FILE beside the config file",
        "The config refers to it as \${env:NAME}; a variable of the environment wins over the file's. The value " +
            "is read from the terminal, or from the input's first line when it is no terminal.",
    ) { given ->
        val name = requireSecretName(given.single("secret set needs one name, such as DEEPSEEK_API_KEY."))
        CliAction.Act { secretSet(it, name) }
    },
    CliCommand(
        listOf("secret", "list"),
        "[$JSON]",
        SECRETS,
        "list the names of the saved secrets",
        flags = setOf(JSON),
    ) { given ->
        noArguments(given, "secret list")
        CliAction.Act { secretList(it, given.flag(JSON)) }
    },
    CliCommand(listOf("secret", "unset"), "<NAME>", SECRETS, "remove a saved secret") { given ->
        val name = requireSecretName(given.single("secret unset needs one name."))
        CliAction.Act { secretUnset(it, name) }
    },
    CliCommand(
        listOf("agent", "list"),
        "[$JSON]",
        SHORTCUTS,
        "list the agents, their models and the channel instances they serve",
        flags = setOf(JSON),
    ) { given ->
        noArguments(given, "agent list")
        CliAction.Act { agentList(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("agent", "add"),
        "<id> [--name <name>] [--description <text>] [--model <endpoint>/<model>] [--channel <type>:<name>]... " +
            "[--default] [$RESTART]",
        SHORTCUTS,
        "add an agent",
        "--channel attaches it to a channel instance, --default makes it the instance's default agent; an agent " +
            "that serves no channel is a profile, which needs a --description. $CHANGES",
        flags = setOf("--default", RESTART),
        options = setOf("--name", "--description", "--model", "--channel"),
    ) { given ->
        val id = requireId(given.single("agent add needs one agent id."), "agent id")
        val model = given.option("--model")?.let(::requireModel)
        CliAction.Act {
            agentAdd(
                it,
                id,
                given.option("--name"),
                given.option("--description"),
                model,
                given.all("--channel"),
                given.flag("--default"),
                given.flag(RESTART),
            )
        }
    },
    CliCommand(listOf("agent", "remove"), "<id> [$RESTART]", SHORTCUTS, "remove an agent", CHANGES, setOf(RESTART)) {
        val id = requireId(it.single("agent remove needs one agent id."), "agent id")
        CliAction.Act { cli -> agentRemove(cli, id, it.flag(RESTART)) }
    },
    CliCommand(
        listOf("model"),
        "[<endpoint>/<model>] [$AGENT <id>] [$JSON] [$RESTART]",
        SHORTCUTS,
        "show the agents' models, or set an agent's default model",
        "Without $AGENT it sets the model of the only agent, or of the only default one. $CHANGES",
        flags = setOf(JSON, RESTART),
        options = setOf(AGENT),
    ) { given ->
        given.atMost(1, "model takes one model.")
        val model = given.operands.firstOrNull()?.let(::requireModel)
        val agent = given.option(AGENT)?.let { requireId(it, "agent id") }
        CliAction.Act { model(it, model, agent, given.flag(JSON), given.flag(RESTART)) }
    },
    CliCommand(
        listOf("channel", "add"),
        "telegram [<name>] [--admin <user id>]... [$AGENT <id>] [--token-variable <NAME>] [--api-base-url <url>] " +
            "[$RESTART]",
        SHORTCUTS,
        "add a Telegram bot, whose token Telegram checks first",
        "The instance is named main unless <name> says. The token, typed without echo, goes into $SECRETS_FILE " +
            "as ALEXANDRITE_TELEGRAM_<NAME>_TOKEN unless --token-variable names another; --admin names the " +
            "Telegram user ids of the bot's admins, and the only agent serves it unless $AGENT names one. $CHANGES",
        flags = setOf(RESTART),
        options = setOf("--admin", AGENT, "--token-variable", "--api-base-url"),
    ) { given ->
        val type = given.operands.firstOrNull() ?: throw UsageException("channel add needs the channel type: telegram.")
        if (type != "telegram") throw UsageException("channel add knows the channel type telegram, not $type.")
        given.atMost(2, "channel add telegram takes one instance name.")
        val name = requireId(given.operands.getOrNull(1) ?: "main", "instance name")
        val admins = given.all("--admin").map {
            it.toLongOrNull() ?: throw UsageException("--admin needs a Telegram user id, such as 123456789.")
        }
        val variable = given.option("--token-variable")?.let(::requireSecretName)
        val agent = given.option(AGENT)?.let { requireId(it, "agent id") }
        CliAction.Act {
            channelAddTelegram(it, name, admins, agent, variable, given.option("--api-base-url"), given.flag(RESTART))
        }
    },
    CliCommand(
        listOf("mcp", "add"),
        "<name> (--url <url> | <command> [<argument>...]) [$RESTART]",
        SHORTCUTS,
        "add an MCP server reached over HTTP or run as a command",
        "Put -- before a command whose arguments start with -. $CHANGES",
        flags = setOf(RESTART),
        options = setOf("--url"),
    ) { given ->
        val name = given.operands.firstOrNull() ?: throw UsageException("mcp add needs the server's name.")
        val url = given.option("--url")
        val command = given.operands.drop(1)
        if ((url == null) == command.isEmpty()) {
            throw UsageException("mcp add needs either --url or a command to run.")
        }
        CliAction.Act { mcpAdd(it, name, url, command, given.flag(RESTART)) }
    },
    CliCommand(
        listOf("mcp", "list"),
        "[$JSON]",
        SHORTCUTS,
        "list the MCP servers, with their states when Alexandrite runs",
        flags = setOf(JSON),
    ) { given ->
        noArguments(given, "mcp list")
        CliAction.Act { mcpList(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("mcp", "remove"),
        "<name> [$RESTART]",
        SHORTCUTS,
        "remove an MCP server",
        CHANGES,
        setOf(RESTART),
    ) {
        val name = it.single("mcp remove needs the server's name.")
        CliAction.Act { cli -> mcpRemove(cli, name, it.flag(RESTART)) }
    },
    CliCommand(
        listOf("mcp", "login"),
        "<name> [--address <address>]",
        SHORTCUTS,
        "log the running Alexandrite in to an MCP server reached over HTTP",
        "Prints the address to open in a browser, then takes the address the browser ended on, from the terminal " +
            "or from --address.",
        options = setOf("--address"),
    ) { given ->
        val name = given.single("mcp login needs the server's name.")
        CliAction.Act { mcpLogin(it, name, given.option("--address")) }
    },
    CliCommand(listOf("mcp", "logout"), "<name>", SHORTCUTS, "forget the login to an MCP server") { given ->
        val name = given.single("mcp logout needs the server's name.")
        CliAction.Act { mcpLogout(it, name) }
    },
    CliCommand(
        listOf("pairings"),
        "[$JSON]",
        CHATS,
        "list the chats waiting to be paired, then the paired chats",
        flags = setOf(JSON),
    ) { given ->
        noArguments(given, "pairings")
        if (given.flag(JSON)) CliAction.Act { pairings(it, true) } else CliAction.Ask(ControlRequest.ListPairings)
    },
    CliCommand(listOf("pair"), "<code>", CHATS, "pair the chat that was offered <code>") { given ->
        val code = given.operands.joinToString(" ").ifEmpty { throw UsageException("pair needs the code to approve.") }
        CliAction.Ask(ControlRequest.Approve(code))
    },
    CliCommand(listOf("deny"), "<code>", CHATS, "drop the request of the chat that was offered <code>") { given ->
        val code = given.operands.joinToString(" ").ifEmpty { throw UsageException("deny needs the code to deny.") }
        CliAction.Ask(ControlRequest.Deny(code))
    },
    CliCommand(listOf("unpair"), "<chat>", CHATS, "unpair a chat, such as $EXAMPLE_CHAT") { given ->
        val chat = given.single("unpair needs one chat address, such as $EXAMPLE_CHAT.")
        if (ChatAddress.parseOrNull(chat) == null) {
            throw UsageException("'$chat' is no chat address, such as $EXAMPLE_CHAT.")
        }
        CliAction.Ask(ControlRequest.Unpair(chat))
    },
    CliCommand(
        listOf("chats"),
        "[$JSON]",
        CHATS,
        "list the paired chats and the agent that serves each",
        flags = setOf(JSON),
    ) { given ->
        noArguments(given, "chats")
        CliAction.Act { chats(it, given.flag(JSON)) }
    },
)
