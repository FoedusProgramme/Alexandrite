# Config

How Alexandrite's config file is laid out, how config.get, config.set and config.unset change it, which changes need a restart, how secrets are written, and the keys of the agent plugin.

## The config file

One JSON file, `alexandrite.json`, configures the host and every plugin, each plugin under its own root: `app`,
`agent`, `tools`, `mcp`, `channels.telegram`, `providers.<provider>` (such as `providers.deepseek`),
`automations.schedule`, `automations.heartbeat`, and `plugins.<id>` for a plugin that is not built in. A plugin's
`enabled` key, such as `mcp.enabled`, switches it off with `false`. A key is written as its path of dot-separated
names, such as `agent.agents.main.models`.

## Reading and changing it

- config.get with a path shows that value, without one the whole file. Secrets show as their `${env:NAME}` reference or
  as `[secret]`, never as their values.
- config.set takes a path and a JSON value (a string, number, boolean, list or object); config.unset removes the value
  so that its default applies. The whole file must still pass the checks of a start, or nothing changes. Both answer
  which changes took effect at once and which need a restart.
- Operators do the same with `alexandrite config get|set|unset|edit` (see alexandrite-app/cli).

config.set and config.unset are external actions, so each call needs an approval in `ask` and is never cleared by an
allow rule or `allowedit`. A change that reaches `agent.floor`, and every change in a tainted conversation, goes to a
person whatever the mode.

## Restart or live

Only the hard floor, `agent.floor`, applies at once. Every other change is written to the file and takes effect when
Alexandrite restarts, which an operator does (`alexandrite restart`, or a CLI change with `--restart`). Tell the user
when a change waits for a restart.

## Secrets

Secrets (API keys, bot tokens, MCP `env` and `headers` values) are written as references `${env:NAME}`. The value comes
from the environment, else from `secrets.env` beside the config file, where an operator stores it with
`alexandrite secret set NAME` without it appearing anywhere else. Never put a secret's value into the config or a
chat: write the reference and ask the operator to set the secret; config.set refuses a secret given as a value.

## Providers and models

Models are reached through endpoints, configured at `providers.<provider>.endpoints.<endpoint>` (`baseUrl`, `apiKey`,
`headers`, `models`). A model is named `<endpoint>/<model>` everywhere, such as `deepseek/deepseek-chat`.
`providers.<provider>.endpoints.<endpoint>.models.<model>` may set `contextWindow`, `maxOutputTokens` and `price` (US
dollars per million tokens: `input`, `output`, `cached`).

## The agent plugin's keys

Host-wide, under `agent`:

- `agent.trustedFolders`: folders that are trusted without asking (see alexandrite-agent/taint-and-trust).
- `agent.floor.deny`, `agent.floor.ask`: the hard floor (see alexandrite-agent/permissions).
- `agent.maxConcurrentTurns` (4), `agent.maxJobsPerChat` (4), `agent.maxBackgroundRuns` (4),
  `agent.maxBackgroundRunsPerChat` (4).
- `agent.queue.perKey` (20 waiting turns per agent and chat), `agent.messaging.maxMessagesPerRoot` (20 agent messages
  per root turn), `agent.models.retries` (3),
  `agent.maxMediaBytes` (20000000), `agent.persona.maxFileChars`, `agent.persona.maxTotalChars`,
  `agent.conversationDirectory.maxBytes`, `agent.conversationDirectory.keepDays`.

Per agent, under `agent.agents.<id>`:

- `name`, `description`; `instructions` (inline persona texts) and `instructionFiles` (see
  alexandrite-agent/persona-and-skills).
- `model`, or `models`: `<endpoint>/<model>` names, the first the default; a chat picks another with `/model`, and a
  model that is down moves the turn to the next one. `reasoning` (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`,
  `max`), `maxOutputTokens`, `temperature`.
- `language` (a tag such as `zh-CN`) and `zone` (such as `Asia/Shanghai`), which chats may override.
- `channels`: the channel instances it serves, keyed `<type>:<name>`, each `{"default": true, "home": "<chat>"}`, and
  `linkedChats`: groups of chat addresses that share one conversation.
- `workspace`: its own directory, by default `<data dir>/workspaces/<id>`.
- `agent.agents.<id>.tools.allow` and `agent.agents.<id>.tools.deny` (globs over tool names such as `fs.*`, deny
  winning) and `agent.agents.<id>.tools.forGuests` (the tools a guest's turn may call).
- `permission`: `mode`, `allow`, `deny`, `trust`, `judge`, `judgeTimeoutSeconds`, `taintedNetworkReads`.
- `sandbox`: `type` (`host`, `auto`, `seatbelt`, `bubblewrap`), `allowWrite`, `denyRead`, `protect`,
  `network.allowedDomains`, `autoAllow`, `strict` (see alexandrite-tools/exec).
- `budget.daily` and `budget.monthly`, each `tokens` and/or `money` (see alexandrite-agent/usage-and-budgets);
  `maxRounds` and `maxTurnTokens` bound one turn.
- `toolTimeoutSeconds` (1800) and `foregroundSeconds` (300, after which a call moves to the background).
- `compaction`: `trigger` (0.95 of the context window), `target` (0.20), `clearAt` (0.60), `keepTurns` (2),
  `keepToolRounds` (5), `maxSummaryTokens`, `model`.

An agent with no `channels` is a profile for sub-agents (see alexandrite-agent/profiles-and-delegation).
