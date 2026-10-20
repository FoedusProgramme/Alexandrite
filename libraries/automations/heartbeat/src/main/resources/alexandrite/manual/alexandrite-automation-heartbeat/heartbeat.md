# Heartbeats

How an agent's heartbeat works: turns on a timer that belong to no chat, how to configure one, and how a beat reaches people.

## A beat

A heartbeat gives an agent a turn every so often, in its own heartbeat conversation that belongs to no chat. The turn
starts with an [alexandrite:initiated] header of kind `heartbeat` and the heartbeat's prompt; its reply reaches nobody.
To tell someone something, send it with chat.send, to `home` (the agent's home chat) or a chat from chat.list;
otherwise end without a reply. A beat runs as nobody: its gated calls go to an operator, and it does not start while the
previous one still runs.

The default prompt asks the agent to look at the time, its notes and its running jobs, and to message someone only
when something needs their attention.

## Configuring

An operator sets a heartbeat at `automations.heartbeat.agents.<id>`:

- `every`: how often, such as `30m`, `2h` or `1h30m`, at least 5 minutes;
- `prompt`: what each beat asks, the default prompt when unset;
- `activeHours`: the part of the day beats run in, such as `08:00-23:00` or `22:00-06:00`, all day when unset;
- `zone`: the zone of `activeHours`, a region such as `Asia/Shanghai` or an offset such as `+08:00`, the host's when
  unset.

Changes take effect after a restart. An agent without a home chat is warned about at start: a beat can then reach
nobody through `home`. An operator makes a chat the home with `/home` there, or sets `home` in the agent's channel
attachment (`agent.agents.<id>.channels`).

## In chats

`/heartbeat` shows the heartbeat of the chat's agent: its interval, active hours, zone and next beat. A beat's
conversation can become tainted like any other; an operator clears it with `/untaint heartbeat`.
