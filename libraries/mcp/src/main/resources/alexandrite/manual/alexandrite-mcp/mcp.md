# MCP servers

How to add an MCP server under `mcp.servers.<name>`, how its tools are named, gated and trusted, and how to log in to a server that needs OAuth.

## Adding a server

A server is an entry `mcp.servers.<name>` of the config (the name: a lowercase letter, then letters, digits or `_`).
It either runs as a command over stdio or is reached over HTTP:

- `command`, `args`, `env` (variables set over a minimal environment) and `cwd`, for a server that runs as a program,
  such as `{"command": "uvx", "args": ["mcp-server-git", "--repository", "/srv/app"]}`;
- `url` and `headers`, for a server reached over Streamable HTTP, such as `{"url": "https://mcp.example.com/mcp"}`.

`env` and `headers` values are secrets: write them as `${env:NAME}` and have the operator store the value with
`alexandrite secret set NAME`. Further keys of a server:

- `mcp.servers.<name>.tools.allow` and `mcp.servers.<name>.tools.deny`: globs over the server's own tool names, deny
  winning (all tools by default).
- `risk`: globs over its tool names to a risk (`read_only`, `agent_state`, `network_read`, `persistent_state`,
  `external_action`, `workspace_write`, `exec`), the first match winning, such as
  `{"git_status": "read_only", "*": "workspace_write"}`. A tool that no glob matches is exec, unless
  `trustAnnotations` is true and the server marks it read-only (read_only over stdio, network_read over HTTP).
- `trusted`: true when its output should not taint the conversation (false by default).
- `timeoutSeconds` (60), `enabled` (true).
- `sandbox`, for a command: `type` (`host` by default, `auto`, `seatbelt`, `bubblewrap`), `allowWrite`, `denyRead`,
  `protect`, `network.allowedDomains`; its `cwd` stays writable.
- `oauth`, for a url: `clientId`, `clientSecret` and `scopes`; without it the client registers itself.

To add one, the operator runs `alexandrite mcp add <name> --url <url>` or
`alexandrite mcp add <name> <command> <argument>...` (`alexandrite mcp list`, `alexandrite mcp remove <name>`), or
you call config.set with the path `mcp.servers.<name>` and the server's object, which needs an approval. Either way
the server starts when Alexandrite restarts. A launch that runs a shell with `-c`, inline code or a privileged
container is warned about at start.

## Its tools

A connected server's tools are offered as mcp.<server>.<tool>, the tool's name lowercased with other characters as
`_`, shortened with a hash past 64 characters; descriptions start with `[MCP server <name>]`. The tools change as the
server lists them, which the next turn's [alexandrite:tools] message tells. Results come back as text, images and
structured JSON. Each call is gated by its risk like any tool; "Always allow" on an approval allows that tool for the
conversation.

Output of a server that is not trusted taints the conversation, and so do its connection errors.
`/trust mcp.<server>.*` trusts a server's output for the current conversation; `mcp.servers.<name>.trusted` trusts it
for good.

## States and logging in

`/mcp` shows each server's state: connecting, connected (with its protocol and number of tools), down, waiting for a
login, or disabled, with its last error. A server that fails is restarted after 1 s, doubling to 5 min; an operator
restarts one at once with `/mcp restart <server>`.

A server reached over HTTP may need an OAuth login. An operator sends `/mcp login <server>` in a direct chat with the
bot and opens the link it answers with; where the browser runs on another machine, they send back the address the
browser ended on with `/mcp login <server> <address>`. `/mcp logout <server>` forgets the login. From a terminal the
same is `alexandrite mcp login <name>` and `alexandrite mcp logout <name>`. For logins from other machines without
copying addresses, `mcp.publicOrigin` names the address through which browsers reach Alexandrite's callback, served on
the loopback port `mcp.callbackPort`.
