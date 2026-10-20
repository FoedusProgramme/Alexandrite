# Scheduled tasks

How schedule.create, schedule.list and schedule.cancel set up tasks that run later as turns in a chat, and how such a turn runs.

## Creating a task

schedule.create schedules a task in the chat at hand: when it comes due, you get its `prompt` as a turn in that chat,
and your answer is posted there. Give the prompt, written so that it reads well when it comes due, and exactly one
time:

- `at`: a local date-time in the chat's zone, such as `2026-11-02T09:00`, once;
- `in`: a delay such as `30m`, `2h`, `1d` or `1h30m`, once;
- `every`: an interval such as `1h` or `1d`, the first run one interval from now;
- `cron`: five fields (minute hour day-of-month month day-of-week) or a macro such as `@daily`, in the chat's zone,
  such as `0 9 * * MON-FRI`.

A recurring task repeats at most every `automations.schedule.minIntervalMinutes` (5) minutes; a chat holds at most
`automations.schedule.maxPerChat` (50) tasks and an agent `automations.schedule.maxPerAgent` (500). Only an operator's
own message may create or cancel tasks: never a scheduled, heartbeat or other automated turn, nor a sub-agent. In a
tainted conversation a person must allow schedule.create, and `plan` refuses it. Tell the user the task's id
(`t-` and four characters), which they need to cancel it.

## Listing and cancelling

schedule.list shows the chat's tasks: id, when, next run, who set it up and the prompt. schedule.cancel cancels one by
its id. People see them with `/schedule` and cancel one with `/schedule cancel <id>` (operators).

## When a task runs

A task's turn starts with an [alexandrite:initiated] header of kind `reminder`, its id, who set it up, when it was due
and how late it runs. A task made from a clean conversation carries its operator's mandate: its turn runs on their
behalf ("On behalf of" in the header) and its prompt is trusted. A task without a mandate runs as nobody, untrusted,
and its gated calls go to an operator. A task that becomes tainted while it runs drops to nobody from then on.

If the host was down when a task came due, its latest missed run still runs when it is no older than
`automations.schedule.missedWindowHours` (24); otherwise the chat is told that it was missed. A run cut off by a
restart is reported too, and overlapping runs of one task are skipped.

## Scheduled task or heartbeat?

A task belongs to a chat: it runs in the chat's conversation, its answer is posted there, and it suits reminders and
timed reports. An agent's heartbeat, set in the config at `automations.heartbeat.agents.<id>`, beats at a fixed
interval within its active hours in a conversation of no chat, runs as nobody, reaches people only through chat.send,
and suits autonomous check-ins (see alexandrite-automation-heartbeat/heartbeat).
