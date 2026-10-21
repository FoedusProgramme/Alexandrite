# Running programs

How command.exec runs programs: commands and argv, the chains it runs itself, its limits, background jobs and monitors, the sandbox, and how its calls are gated and taint.

## A call

command.exec runs one program without a shell. Give `command`, one line split as a shell splits it (quotes,
backslashes, a leading `~`) but without pipes, redirects, variables, globs or substitutions, or `argv`, a list passed
as it is. Run programs directly and set `cwd` (an absolute path, or relative to the workspace; the workspace by
default) instead of `cd`. Wrappers such as `env A=1 …` and `timeout 5 …` are seen through.

The program gets no input and a reduced environment, plus the variables that `tools.exec.environment` names. Its
stdout and stderr come back together with the exit code, cut to `tools.exec.maxOutputChars` (30000) characters: the
start and the end of a long output. It is stopped, with every process it started, after `timeoutSeconds`, at most
`tools.exec.maxTimeoutSeconds` (1800), which is also the default.

## Chains

A POSIX shell script given with `-c` (`sh`, `bash`, `dash`, `zsh`, `ksh`, `mksh`) that uses only words, quotes,
`&&`, `||`, `;`, `|`, line breaks, `cd <dir>`, `2>&1`, `>&2`, redirects to `/dev/null` and, but in zsh, the wildcards
`*` and `?`, is run by command.exec itself, step by step, as plain programs, such as
`sh -c 'make 2>&1 | tail -5 && make test'`. Each step is checked and shown on its own. Any other script, and any
program that runs code it is given (shells, interpreters with inline code, `xargs`, `sudo`, `ssh` …), is opaque:
Alexandrite cannot see what it runs, so it needs approvals more often, no "always allow" is offered for it, and its
output taints. Prefer separate calls or plain chains.

## Background and monitors

`background: true` starts the call as a job at once; any call that runs past the agent's `foregroundSeconds` (300)
moves to the background by itself. The result comes later as an [alexandrite:job] message; agent.jobs and agent.stop
list and stop jobs.

A `monitor` (`filter`: a literal substring of the lines to report; `batchSeconds`, `intervalSeconds`,
`maxLifetimeSeconds`) starts a background job that wakes you with the matching output lines in batches until the
program exits or the monitor's lifetime ends, such as for a server's log or a long build. Excess lines are dropped with
a count.

## Risk and gating

A call is an exec call, gated in every mode but `bypass` unless an allow rule or the sandbox clears it (`allowedit`
does not). A call whose programs are all vetted read-only ones (`ls`, `cat`, `head`, `tail`,
`grep`, `wc`, `stat`, `pwd`, `date`, `echo`, `which` …) is read_only and runs at once. An allow rule names the start of
the argument list, such as `command.exec(./gradlew test)`, and a program named by a path is bound to its real path.
The floor refuses calls that reach protected places.

Programs taint the conversation when they read the network (`curl`, `wget`, `ssh`, `git clone|fetch|pull|push`,
package managers installing or fetching, `docker run|pull|exec` …) or run code they are given, and so do paths they
reach outside trusted places. `/trust command.exec(<program>)` trusts a program's output for the conversation.

## The sandbox

An agent whose `agent.agents.<id>.sandbox.type` is `auto` (Seatbelt on macOS, bubblewrap on Linux), `seatbelt` or
`bubblewrap` runs its commands in that sandbox; `host`, the default, runs them on the host. In the sandbox programs:

- write only in the workspace, the conversation's directory, trusted folders, temp and
  `agent.agents.<id>.sandbox.allowWrite`, never in the protected names (`agent.agents.<id>.sandbox.protect`, by default
  shell start files, `.git/hooks`, `.git/config`, `.ssh`, `.vscode`, `.idea`, `.claude`, `.agents`, `.mcp.json` and
  Alexandrite's own places);
- read everything but the floor's places and `agent.agents.<id>.sandbox.denyRead`;
- reach the network only through a proxy, to the hosts of `agent.agents.<id>.sandbox.network.allowedDomains` and those
  a call names in `network`, which needs an approval.

With `agent.agents.<id>.sandbox.autoAllow` (the default) a call that runs wholly inside the sandbox runs without asking
in `ask`, `allowedit` and `auto`; in a tainted conversation only when it reaches no host. `sandbox: false` runs a call
on the host, which needs approval more often and is refused when `agent.agents.<id>.sandbox.strict` is set.
