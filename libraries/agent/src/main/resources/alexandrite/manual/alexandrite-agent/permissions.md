# Permissions

How Alexandrite decides whether a tool call runs: risks, permission modes, allow and deny rules, the hard floor, the judge and approvals.

## How a call is decided

Every tool call passes, in order: the tool's own review (it may refuse, for example a protected path), deny rules, the
call's risk against who started the turn and whether the conversation is tainted, then the chat's permission mode. A
call that is not gated runs at once. A gated call of an operator's turn goes through the mode: allow rules, the judge
in `auto`, or an approval by a person. A gated call of a guest's or an automated turn always goes to an operator.

A call that does not run returns a tag: [alexandrite:plan_mode], [alexandrite:denied by=policy],
[alexandrite:blocked_floor], [alexandrite:denied by=operator], [alexandrite:expired] or [alexandrite:declined].
permission.status shows the chat's mode, the allow rules in force and the taint.

## Risks

Each tool declares a risk, and a built-in tool may lower it for one call (a vetted read-only program of command.exec
is read_only):

- read_only, agent_state: never gated.
- network_read (web.fetch, web.search): not gated, unless the conversation is tainted and
  `agent.agents.<id>.permission.taintedNetworkReads` is `gate`.
- persistent_state (lasting changes to the agent, such as its persona files or profiles): gated for guests, automated
  turns and tainted conversations.
- external_action, workspace_write, exec: always gated, but for writes into the conversation's own directory and,
  from a clean conversation of an operator, chat.send and chat.send_file.

## Permission modes

Each chat has a mode per agent, set with `/mode <mode>` by an operator; a chat that sets none uses
`agent.agents.<id>.permission.mode`, else `ask`. From strictest to loosest:

- `plan`: no gated call runs; describe the exact call you would make and why.
- `manual`: a person is asked about every gated call; allow rules do not apply.
- `ask` (the default): allow rules first, then a person.
- `allowedit`: file writes that the floor allows run (not writes to instruction places), the rest as in `ask`.
- `auto`: allow rules, then the judge, then a person when the judge asks.
- `bypass`: every gated call runs, for a limited time: `/mode bypass 2h`, one hour by default, 24 hours at most, after
  which the agent's default mode applies.

A sub-agent's calls take the strictest mode of its chain. When the agent has a sandbox with `autoAllow` (the default),
a command that runs wholly inside it runs without asking in `ask`, `allowedit` and `auto`.

## Allow and deny rules

An allow rule names a tool, such as `fs.write`, or a command.exec call by the start of its argument list, such as
`command.exec(git status)`. Rules come from the config (`agent.agents.<id>.permission.allow`) or from "Always allow" on
an approval, which allows the call for the rest of the conversation: `/new` drops it. `/allow` lists the rules in force
and `/allow revoke <number>` revokes one. No rule is offered for programs that run code they are given (shells,
interpreters, `env`, `sudo`, `ssh` …) or for persistent_state, and none applies in `manual`.

Deny rules, written the same way in `agent.agents.<id>.permission.deny`, refuse their calls in every mode.

## The hard floor

The floor refuses places to every tool whatever the mode, rule or approval. It is empty unless the operator fills
`agent.floor.deny` with groups or paths: `credentials` (home credential stores such as `~/.ssh`), `system` (`/dev`,
sensitive `/proc` entries), `alexandrite` (its config, secrets, data, cache and install places, other agents' persona
directories and other conversations' directories), or a path (absolute, `~/…`, or relative to the config file).
`agent.floor.ask` takes the same entries for places where a person must allow each call whatever the mode (`plan`
refuses them). Floor changes apply at once, without a restart, and any change to `agent.floor` needs a person.

## Instruction places

The config file and its backup, `secrets.env`, persona and host skill directories, a trusted folder's `AGENTS.md`,
`CLAUDE.md`, `.agents/skills` and `.claude/skills`, and the sandbox's protected names are instruction places: writes to
them are never cleared by `allowedit` or allow rules, `auto` asks the judge, and in a tainted conversation a person
decides.

## The judge

In `auto`, a side model judges each gated call that no rule allows: `agent.agents.<id>.permission.judge`
(`<endpoint>/<model>`, the agent's own model when unset), waiting `judgeTimeoutSeconds` (20). It sees only the call,
what the operator wrote in the turn and the taint, and answers allow, deny or ask; ask goes to a person. It never allows
writes to the config, secrets, persona or skills, and an opaque program of a tainted conversation goes straight to a
person.

## Approvals

An approval request shows the call, its preview (a diff, the command and its working directory, the sandbox, the hosts
it asks for), why it was asked and, in a tainted conversation, where the taint came from. On Telegram it is posted in
the chat for an operator's turn and in the admins' direct chats otherwise, and waits
`channels.telegram.instances.<name>.approvalMinutes` (10); only admins can answer. It has at most three buttons: Allow
("Allow, mark tainted" when the call would taint), Deny, and one of "Always allow 〈rule〉", "Allow and trust 〈root〉",
"Always allow and trust 〈root〉" or "Allow, keep clean" (see alexandrite-agent/taint-and-trust). A person who writes in
the chat instead of answering declines the call: do what their message says.
