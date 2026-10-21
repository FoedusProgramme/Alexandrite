# The alexandrite command

What the operator's `alexandrite` command does on the host: setup, the service, config and secrets, shortcuts for agents, models, channels and MCP servers, pairing chats, and this manual.

Only an operator at the host's terminal runs these; you cannot. When someone asks how to do one of these things, tell
them the command. `alexandrite --help` lists every command and `alexandrite <command> --help` explains one. Every
command takes `--config <file>`, `--data-dir <dir>` and `--cache-dir <dir>`; the defaults come from
`ALEXANDRITE_CONFIG`, `ALEXANDRITE_DATA_DIR` and `ALEXANDRITE_CACHE_DIR`, else the XDG variables, else the platform's
places (`~/.config/alexandrite/alexandrite.json` on Linux).

## Setup and the service

- `alexandrite init` — set Alexandrite up step by step: the model and its provider, a Telegram bot, its admin
  (claimed by sending a one-time code to the bot), the language and zone, the config and secrets, and the background
  service (systemd, launchd or a Windows task). `--only <step>` redoes one step (`model`, `telegram`, `admin`,
  `language`, `service`); `--non-interactive` takes flags and the environment.
- `alexandrite run` — run in the foreground.
- `alexandrite start`, `stop`, `restart`, `status` — control the background service; `status` tells whether
  Alexandrite runs and its channel instances.
- `alexandrite logs [-f] [-n <lines>]` — print the service's log, and follow it.
- `alexandrite doctor` — check Java, the config, the endpoints, Telegram, the secrets, the disk and the service.
- `alexandrite update [--version <release>]` — install the latest release, or the one named, checking it against the
  config before switching and restarting the service; the release before is kept, and a failed update switches back.
- `alexandrite uninstall [--keep-data] [--yes]` — remove the service, the program and, unless kept, config and data.

## Config and secrets

- `alexandrite config get [<key>]` — print a value, or the whole file.
- `alexandrite config set <key> <value> [--restart]` — set a value, given as JSON or as a plain string.
- `alexandrite config unset <key> [--restart]` — remove a value so its default applies.
- `alexandrite config edit` — edit the file in `$VISUAL` or `$EDITOR`; it is written only once it passes.
- `alexandrite config check` — check the file as a start would.
- `alexandrite config path` — print where the config file is (`--json` adds secrets, data and cache).
- `alexandrite secret set <NAME>`, `secret list`, `secret unset <NAME>` — keep secrets in `secrets.env` beside the
  config file, referred to as `${env:NAME}`.

A change goes through the running Alexandrite, which checks it as a start would and says what needs a restart;
`--restart` restarts the service once it is written. With none running the command writes the file itself.

## Shortcuts

- `alexandrite agent list`, `agent add <id> [--model <endpoint>/<model>] [--channel <type>:<name>] [--default]`,
  `agent remove <id>` — agents; one without a channel is a profile.
- `alexandrite model [<endpoint>/<model>] [--agent <id>]` — show the agents' models, or set one's default.
- `alexandrite channel add telegram [<name>] [--admin <user id>]` — add a Telegram bot and claim its admin.
- `alexandrite mcp add <name> (--url <url> | <command> [<argument>...])`, `mcp list`, `mcp remove <name>`,
  `mcp login <name>`, `mcp logout <name>` — MCP servers (see alexandrite-mcp/mcp).

## Chats

- `alexandrite pairings` — the chats waiting to be paired, then the paired chats.
- `alexandrite pair <code>` — pair the chat that was given the code; `alexandrite deny <code>` drops its request.
- `alexandrite unpair <chat>` — unpair a chat, such as `telegram:main:123456789`.
- `alexandrite chats` — the paired chats and the agent that serves each.

## The manual

- `alexandrite manual` — the pages of this manual; `alexandrite manual <topic>` prints one;
  `alexandrite manual --search <words>` prints the sections that match best, as manual.read does.
