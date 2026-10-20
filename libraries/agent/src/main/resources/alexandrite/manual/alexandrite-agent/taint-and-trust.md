# Taint and trust

What makes a conversation tainted, what a taint changes, the question asked before it, and how folders, domains, programs, MCP servers and people are trusted.

## Taint

A conversation is tainted once it took in content that nobody trusts, which might carry instructions planted by
someone else. The mark stays for the rest of the conversation: `/new` starts a clean one, and an operator's `/untaint`
clears it once they checked what came in: in a chat, every agent's conversation there, or one agent's with
`/untaint <agent>`; `/untaint heartbeat [<agent>]` clears an agent's heartbeat conversation.
permission.status and `/settings` show whether the conversation is tainted, since when and why.

## What taints

- Reading a file outside the trusted places: trusted folders, the agent's workspace, its persona and skill directories
  and the conversation's own directory. Writing does not taint. A file that a tainted conversation wrote taints whoever
  reads it later, in any conversation.
- web.fetch and web.search, unless a trust rule covers them.
- The output of an MCP server, unless the server is trusted (`mcp.servers.<name>.trusted`) or a trust rule covers it,
  and its connection errors.
- command.exec, for programs that read the network or run code they are given (shells, interpreters, `ssh` …) and for
  paths it reaches outside the trusted places. A local program that works in trusted folders without network does not
  taint.
- The messages and files of people whom the agent does not trust, any forwarded message, and a quote that the channel
  does not vouch for.
- A project skill of a folder that nobody trusts, history.search hits from guests' messages of other conversations, a
  message that chat.send brings from a tainted conversation, and what a sub-agent's run took in.

## What a taint changes

- persistent_state calls, such as writes to the agent's persona files and profile changes, need an approval.
- Writes to instruction places (the config, `secrets.env`, persona and skill directories, a trusted folder's
  instruction files and skills) and every config.set or config.unset go to a person.
- schedule.create goes to a person, and a scheduled task that becomes tainted runs as nobody from then on.
- chat.send and chat.send_file are gated like any external action, and carry the taint to the chat they reach.
- In `auto`, the judge is told of the taint, and an opaque program goes to a person.
- A sandboxed command runs without asking only when it reaches no host.
- With `agent.agents.<id>.permission.taintedNetworkReads` set to `gate`, web reads are gated too.
- Approval requests show where the taint came from.

## The question before a taint

When a call of an operator's turn would bring the first untrusted content into a clean conversation, in `ask`,
`manual`, `allowedit` and `auto`, a person gets one question merged with any approval: "Allow and trust 〈root〉" keeps
the conversation clean by trusting the source, "Allow, mark tainted" runs the call and marks the conversation, Deny
refuses it. Where no source can be named, "Allow, keep clean" vouches for this one output. In `bypass` nothing is
asked: the conversation is marked. Turns of guests and automated turns never wait: the conversation is marked at once,
and the operators get a notice with Keep and "Trust 〈root〉 and clear the taint".

## Trust

Trust means "its output does not taint"; it grants no permission and no authority.

- Folders: a trusted folder and everything below it, outside the repositories nested in it, reads without tainting,
  and its project instructions (`AGENTS.md`, `CLAUDE.md`) and project skills load. Host-wide: declared in
  `agent.trustedFolders` or granted with `/trust <absolute path>`; `/untrust <path>` ends it.
- Trust rules, for the current conversation only: `/trust command.exec(git)` trusts a program's output,
  `/trust web.fetch(example.com)` a domain and its subdomains, `/trust mcp.<server>.*` an MCP server. Declared ones
  for every conversation go in `agent.agents.<id>.permission.trust`. A program rule trusts only the programs: paths
  outside trusted places still taint, and trusting a shell or interpreter trusts any script it runs.
- People: an operator's `/trust` in reply to a person's message, or naming them with @, trusts their own messages and
  files for this agent; what they forward or quote still taints. A channel may trust people in its config, such as
  Telegram's `channels.telegram.instances.<name>.trustedUserIds`. Operators are always trusted.

`/trust` alone lists the trusted folders, the trust rules of the conversation and the trusted people. Only operators run
`/trust`, `/untrust` and `/untaint`.
