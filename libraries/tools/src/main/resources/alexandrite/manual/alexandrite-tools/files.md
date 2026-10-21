# Files

How the file tools fs.read, fs.glob, fs.grep, fs.edit and fs.write work, their limits, and which paths they reach.

## Paths

Every file tool takes an absolute path, `~/…` for the home directory, or a path relative to the agent's workspace.
They reach anything the hard floor allows (see alexandrite-agent/permissions); a refused place returns
[alexandrite:blocked_floor]: say so instead of looking for another way in. They open only regular files with one hard
link and never follow symbolic links when they walk a tree.

## Reading

- fs.read returns a UTF-8 text file's lines, each after its number and a tab: at most 2000 lines from `offset` (`limit`
  fewer), each cut after 2000 characters, saying where to go on. It refuses directories, binary files and protected
  places.
- fs.glob lists what lies under a directory and matches a glob, as absolute paths in name order (a directory ends with
  `/`, a link with `@`): `*` within a segment, `**` across segments, `?`, `[abc]`, `{kt,kts}`. At most 500 paths; it
  skips `.git`, `.hg` and `.svn`.
- fs.grep searches text files for a regular expression (Java syntax) or, with `literal`, plain text, with
  `ignoreCase` and a `glob` that picks the files (`*.kt` at any depth). At most 200 lines, each cut after 300
  characters; it skips binary files, files over 16 MiB and links.

Reading a file outside the trusted places taints the conversation (see alexandrite-agent/taint-and-trust).

## Writing

- fs.edit replaces exact text: each edit's `old` must occur once (or, with `all`, every occurrence), the edits apply in
  order, all or none. Use it for changes to an existing file.
- fs.write writes a whole file, creating it and the directories on its path.

Both change an existing file only after fs.read read it in this conversation and while it has not changed since; they
keep the file's line breaks, write text files only (16 MiB at most), and show a unified diff in an approval.

A write is a workspace_write call: gated unless `allowedit`, an allow rule such as `fs.edit`, or `bypass` clears it,
and free inside the conversation's own directory. A write to the agent's own persona directory is persistent_state
(see alexandrite-agent/persona-and-skills), and writes to instruction places are never cleared by `allowedit` or rules.
Writing does not taint; a file that a tainted conversation wrote taints whoever reads it later.
