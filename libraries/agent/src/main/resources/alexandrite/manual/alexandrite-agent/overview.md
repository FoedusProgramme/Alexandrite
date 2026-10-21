# What Alexandrite is

What Alexandrite is, and how its agents, chats, conversations, jobs and sub-agents fit together.

## Alexandrite

Alexandrite is a host program that runs one or more agents in the background. Plugins add everything else: channels
such as Telegram that carry messages, model providers, tools, chat commands, MCP servers, scheduled tasks and
heartbeats. One JSON config file (`alexandrite.json`) configures every plugin; the operator manages the host with the
`alexandrite` command (see alexandrite-app/cli).

## Agents

An agent is configured at `agent.agents.<id>`: its name, models, persona, tools, permission settings, workspace and the
channel instances it serves (see alexandrite-agent/config). An agent serves a channel instance, such as one Telegram
bot, when its `agent.agents.<id>.channels` names the instance as `<type>:<name>`, for example `telegram:main`. Several
agents may serve one instance: then exactly one is its default (`"default": true`), and a chat switches agents with
`/agent <id>`. An agent that serves no channel is a profile, which other agents run as sub-agents.

## People: operators and guests

The operators of a chat are the admins of its channel instance (for Telegram, `adminUserIds`). An operator's message
starts an operator's turn: tools, permission modes, allow rules and the judge apply to it. Anyone else in a chat is a
guest: a guest's turn may use only the tools in `agent.agents.<id>.tools.forGuests` (none by default), and its gated
calls always go to an operator. Turns that a plugin starts, such as heartbeats, run as nobody unless a scheduled task
carries its operator's mandate.

## Chats and conversations

A chat is one place on a channel, written as an address `<channel>:<instance>:<chat>`, with `#<thread>` for a thread,
such as `telegram:main:123456789`. Each agent keeps one conversation per chat: the history it sees in every turn.
`/new` seals the current conversation and starts a fresh one; the old one stays searchable with history.search.
`/new` also drops what was granted for the conversation only: allow rules from approvals, trust rules and the taint.

A long conversation is compacted: once a request fills most of the model's context window, the older part is folded
into a summary (`agent.agents.<id>.compaction`), and old tool results may read [alexandrite:cleared]. `/compact`
compacts at once and `/context` shows how full the context is.

Each conversation has its own directory, private to it, for files it makes and receives; files sent in the chat are
saved in its `inbox/`. It is kept for `agent.conversationDirectory.keepDays` (7) days after the conversation is
sealed. The agent's workspace, by default `<data dir>/workspaces/<agent id>`, is shared by all its conversations.

Chats can share one conversation: an operator links two chats with `/link` (or `agent.agents.<id>.linkedChats`) and
unlinks them with `/unlink`. An agent's home chat (`/home`, or `home` in its channel attachment) is where chat.send
"home" goes and where notices for no particular chat go.

## Turns and jobs

A turn is the agent's answer to what started it: a message, a scheduled task, a heartbeat, the end of a job, a
delegated task or a message of another agent. Its tool calls run in rounds; `maxRounds` and `maxTurnTokens` of the agent
can bound a turn.

A tool call that runs longer than `agent.agents.<id>.foregroundSeconds` (300 s) moves to the background as a job, and
command.exec and agent.delegate can start one at once with `background: true`. The turn goes on; the job's result comes
later in a user message with an [alexandrite:job] header and starts a new turn. A chat runs at most
`agent.maxJobsPerChat` (4) jobs at once. agent.jobs lists them and agent.stop stops one; people use `/jobs` and
`/jobs stop <id>`, as `/cancel` stops only the running answer.

## Sub-agents and other agents

agent.delegate hands one task to a sub-agent that runs a profile: a fresh conversation with its own instructions,
model and tools, whose final reply is all that comes back (see alexandrite-agent/profiles-and-delegation). agent.send
sends another configured agent a message that it takes for the person your turn acts for; its reply comes later as a
separate turn of yours whose answer is not posted (see alexandrite-agent/messages).

## Finding out more

The manual's pages: call manual.read without arguments for the list, with a topic for a page, or with a query to search.
permission.status shows this chat's permission mode, allow rules and taint. config.get shows the config file.
