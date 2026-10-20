# Profiles and delegation

How an agent hands a task to a sub-agent with agent.delegate, what a profile is, and how profiles are listed, created and changed.

## Delegation

agent.delegate runs one task as a sub-agent: a new conversation with a profile's instructions, model and tools, which
sees only the task you give it (with `context: "fork"`, also this conversation so far) and returns only its final
reply. Give it the whole brief: what to do, what you know that it needs, and what to report.

- By default the call waits for the run; several waiting calls run one after another.
- `background: true` starts the run as a job and returns at once; its result comes later as an [alexandrite:job]
  message. A chat runs at most `agent.maxBackgroundRunsPerChat` (4) background runs, `agent.maxBackgroundRuns` (4) in
  all.
- Each result ends with the run's id (`r-…`); `resume` with that id gives the same sub-agent a follow-up task with what
  it already knows.
- Its tool calls need the same permissions as yours, in the stricter of the chat's mode and its profile's `mode`, and it
  has no tool you may not call. Sub-agents may delegate in turn, three levels deep at most.
- What a run took in that taints, taints your conversation too.

## Profiles

A profile is what a sub-agent runs as: an id, a name, a description that a delegating agent reads to choose it,
instructions, and optionally a model and reasoning effort (the delegating agent's by default), allowed and denied tool
globs, a permission mode that only tightens, `maxRounds` and `maxTurnTokens`. agent.delegate lists the profiles in its
description. They come from:

- The config: every `agent.agents.<id>` that serves no channel is a profile, with its persona as its instructions.
- Plugins: the built-in `general` profile, a sub-agent with your tools, model and settings for any self-contained
  task; a config entry `general` replaces it.
- The agent itself: profile.create makes one, and profile.edit and profile.remove change or remove those it created.
  These are persistent_state calls: an operator's clean turn runs them, others need an approval.

profile.list and `/profiles` show every profile with its model, tools, mode and source.
