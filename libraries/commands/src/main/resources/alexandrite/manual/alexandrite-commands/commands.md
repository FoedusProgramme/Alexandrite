# Chat commands

Every chat command of this version, one line each, with who may run it by default on Telegram.

People send commands in a chat; they are handled by Alexandrite, not by you, and the next turn lists what ran in an
[alexandrite:commands] message, which needs no answer. When someone asks how to do something these commands do, tell
them the command. Who may run what: admins (the chat's operators) run everything; for others, "anyone", "direct"
(anyone in a direct chat, admins elsewhere), "unlinked direct" (anyone in a direct chat linked with no other chat,
admins elsewhere) or "admins". Where it differs, the rule for the bare command comes first, then the one with
arguments. A bot's `commands` config can change the rules (see alexandrite-channel-telegram/telegram).

## Conversation

- `/new` — start a new conversation with this chat's agent; unlinked direct.
- `/cancel` — stop the answer that this chat's agent is giving here, `/cancel <id>` that of agent `<id>`; waiting
  messages are answered next and jobs go on; direct.
- `/jobs` — list the jobs running in this chat, each with its agent; `/jobs all` lists those of every chat, only in a
  direct chat; `/jobs stop <id>` stops one, of any chat for an operator; anyone, then admins.
- `/compact` — fold the older part of the conversation into its summary; `/compact <focus>` names what it should keep
  in view; unlinked direct.
- `/context` — how much of the model's context the conversation takes; anyone.
- `/usage` — the tokens and money the agent used here and in all, and its budgets; `/usage all` adds every agent;
  anyone, then admins.

## Agent and settings of this chat

- `/settings` — the chat's agent, model, reasoning, language, zone, mode, home, links and taint; anyone.
- `/agent` — the agents of this channel; `/agent <id>` switches, `/agent reset` goes back to the default; anyone, then
  direct.
- `/model` — the model; `/model <number>` picks one of the agent's models, `/model <endpoint>` lists an endpoint's
  models, `/model <endpoint>/<model>` switches, `/model reset`; anyone, then admins.
- `/reasoning` — the reasoning effort; `/reasoning <effort>`, `/reasoning reset`; anyone, then unlinked direct.
- `/language` — the language the agent answers in; `/language <tag>`, `/language reset`; anyone, then unlinked direct.
- `/timezone` — the zone the agent tells times in; `/timezone <zone>`, `/timezone reset`; anyone, then unlinked direct.
- `/mode` — the permission mode; `/mode <mode>`, `/mode bypass <duration>` (such as `30m`, `2h`, `1d`), `/mode reset`;
  anyone, then admins.
- `/home` — make this chat the agent's home chat; `/home off`, `/home reset`; admins.
- `/link` — get a code that shares this chat's conversation with another chat, where an operator sends
  `/link <code>`; admins.
- `/unlink` — take this chat out of its linked chats; `/unlink <chat>` takes another out; admins.
- `/profiles` — the profiles a delegating agent may run as sub-agents; anyone.
- `/help` — the commands you may run in this chat; anyone.

## Permissions and trust (operators only)

- `/allow` — the allow rules in force here; `/allow revoke <number>` revokes one.
- `/trust` — what is trusted; `/trust <folder>` trusts an absolute folder, `/trust <rule>` such as
  `/trust command.exec(git)` trusts the output of some calls for the conversation, `/trust` in reply to someone or
  naming them with @ trusts that person.
- `/untrust <folder>`, `/untrust <rule>`, or in reply or with @ — stops trusting.
- `/untaint` — clears the taint of every agent's conversation in this chat (each agent has its own here);
  `/untaint <agent>` clears one agent's; `/untaint heartbeat [<agent>]` clears an agent's heartbeat conversation.

## Other plugins

- `/mcp` — the MCP servers and their states; `/mcp restart <server>`, `/mcp login <server>`, `/mcp logout <server>`
  (login and logout in a direct chat); anyone, then admins.
- `/schedule` — the tasks scheduled in this chat; `/schedule cancel <id>`; anyone, then admins.
- `/heartbeat` — the agent's heartbeat: its interval, active hours and next beat; anyone.
- `/streaming` — how replies come: `edit` or `natural`, `quote` or `noquote`, `default`; anyone, then direct.
- `/<skill name>` — asks the agent to load that skill; anyone who may message it.
