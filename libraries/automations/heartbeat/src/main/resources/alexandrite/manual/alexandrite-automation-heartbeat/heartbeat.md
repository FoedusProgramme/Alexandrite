# Heartbeats

How an agent's heartbeat works: turns on a timer that belong to no chat, how to set one up, how a beat reaches people, and when a scheduled task fits better.

## A beat

A heartbeat gives an agent a turn every so often, in its own heartbeat conversation that belongs to no chat. The turn
starts with an [alexandrite:initiated] header of kind `heartbeat` and the heartbeat's prompt; its reply reaches nobody.
To tell someone something, send it with chat.send, to `home` (the agent's home chat) or a chat from chat.list;
otherwise end without a reply. A beat runs as nobody: its gated calls go to an operator through the channel of the
agent's first attachment (on Telegram, the admins' direct chats), and it does not start while the previous one still
runs. A chat.send of an untainted beat needs nobody; once the heartbeat conversation is tainted it asks too, until an
operator clears it with `/untaint heartbeat`.

The default prompt asks the agent to look at the time, its notes and its running jobs, and to message someone only
when something needs their attention.

## Setting one up

A heartbeat lives in the config at `automations.heartbeat.agents.<id>`:

- `automations.heartbeat.agents.<id>.every`: how often, such as `30m`, `2h` or `1h30m`, at least 5 minutes;
- `automations.heartbeat.agents.<id>.prompt`: what each beat asks, the default prompt when unset;
- `automations.heartbeat.agents.<id>.activeHours`: the part of the day beats run in, such as `08:00-23:00` or
  `22:00-06:00`, all day when unset;
- `automations.heartbeat.agents.<id>.zone`: the zone of the active hours, a region such as `Asia/Shanghai` or an offset
  such as `+08:00`, the host's when unset.

You may set your own with config.set, such as `automations.heartbeat.agents.<id>` to `{"every": "2h"}`, and remove it
with config.unset. Like any config change, the call is gated by the chat's mode, and in a tainted conversation a person
must approve it. An operator does the same with `alexandrite config set`. A change takes effect at once: an added
heartbeat starts, a removed one stops, and a changed one beats next by its new settings, while a beat that runs
finishes. A change that would not pass the checks of a start is refused.

An agent without a home chat is warned about when its heartbeat starts: a beat can then reach nobody through `home`. An
operator makes a chat the home with `/home` there, or sets `home` in the agent's channel attachment
(`agent.agents.<id>.channels`).

## Heartbeat or scheduled task?

- A heartbeat belongs to an agent, a scheduled task to a chat.
- A heartbeat is set in the config; a task is created with schedule.create, and only from an operator's own message.
- A heartbeat beats at a fixed interval within its active hours; a task runs `at` a time, `in` a delay, `every`
  interval or by `cron`.
- A beat runs in the agent's own conversation with no chat and its reply reaches nobody, so it uses chat.send; a task
  runs in the chat's conversation and its answer is posted there.
- A beat runs as nobody; a task made from a clean conversation runs on behalf of the operator who set it.
- Use a heartbeat for autonomous check-ins, a task for reminders and timed reports (see
  alexandrite-automation-schedule/schedule).

## In chats

`/heartbeat` shows the heartbeat of the chat's agent: its interval, active hours, zone and next beat. A beat's
conversation can become tainted like any other; an operator clears it with `/untaint heartbeat`.
