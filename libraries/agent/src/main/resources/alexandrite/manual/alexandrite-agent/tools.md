# Tools

The tools that Alexandrite and its built-in plugins offer, by group, with their risks and when to use which.

Which tools an agent gets is set by `agent.agents.<id>.tools.allow` and `agent.agents.<id>.tools.deny`; a guest's turn
gets only those in `agent.agents.<id>.tools.forGuests`. When the tools change, the next turn starts with an
[alexandrite:tools] message. Each call is decided as alexandrite-agent/permissions tells; the risk is in brackets.

## Files (alexandrite-tools/files)

- fs.read [read_only]: read a text file, with line numbers. Read before you write or edit a file.
- fs.glob [read_only]: list the paths under a directory that match a glob, such as `**/*.kt`.
- fs.grep [read_only]: search files for a regular expression or a plain text.
- fs.edit [workspace_write]: replace exact text in a file read in this conversation; prefer it for changes.
- fs.write [workspace_write]: write a whole file, creating missing directories.

Relative paths resolve against the agent's workspace. Writes into the conversation's own directory need no approval.

## Programs (alexandrite-tools/exec)

- command.exec [exec]: run one program, or a plain chain such as `sh -c 'make 2>&1 | tail -5'`, with a timeout, in
  the background, with a monitor, or in the agent's sandbox. Vetted read-only programs such as `ls`, `cat` or `grep`
  count as read_only.

## Web (alexandrite-tools/web)

- web.fetch [network_read]: fetch a public page and have a side model answer a prompt from it. Taints.
- web.search [network_read]: search the web: titles, links and snippets. Taints.

## Chats and messages (alexandrite-agent/messages)

- chat.send [external_action]: send a message to another chat, home, or answer a message with `replyTo`.
- chat.send_file [external_action]: send a file to this chat.
- chat.list [read_only]: the chats chat.send can reach.
- history.search [read_only]: search the earlier conversations of this chat, or of every chat with scope `all`.
- agent.send [agent_state]: send another configured agent a message; its reply comes later as a separate turn.

## Jobs, sub-agents and profiles (alexandrite-agent/profiles-and-delegation)

- agent.jobs [read_only] and agent.stop [agent_state]: list or stop this chat's background jobs.
- agent.delegate [agent_state]: hand a task to a sub-agent that runs a profile, waiting or in the background.
- profile.list [read_only], profile.create, profile.edit, profile.remove [persistent_state]: the profiles for
  sub-agents.

## The agent itself

- permission.status [read_only]: the chat's permission mode, allow rules and taint.
- config.get [read_only], config.set and config.unset [external_action]: the config file (alexandrite-agent/config).
- skill.load [agent_state]: load a skill's instructions (alexandrite-agent/persona-and-skills).
- manual.read [read_only]: this manual. Its output is trusted.

## Other plugins

- schedule.create, schedule.list, schedule.cancel: tasks that run later in this chat
  (alexandrite-automation-schedule/schedule).
- telegram.react, telegram.pin, telegram.set_title, telegram.poll, telegram.sticker, telegram.member: Telegram's own
  actions (alexandrite-channel-telegram/telegram).
- MCP servers' tools, named mcp.<server>.<tool> (alexandrite-mcp/mcp).

## Choosing

- Use the file tools to read, search and change files, and command.exec for builds, tests, git and other programs;
  run programs directly with `cwd` instead of `cd`, and prefer separate calls to long shell scripts, which are opaque
  and need approvals more often.
- Call tools that do not depend on each other in the same response.
- Use chat.send only to reach another chat or to answer one message in particular: your final answer already reaches
  the chat you answer.
- Start long work in the background (`background: true`) and carry on; its result comes as an [alexandrite:job]
  message.
- Delegate self-contained work that a fresh context does better, such as a review, and give the sub-agent the whole
  brief.
