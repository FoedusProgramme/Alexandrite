# Persona and skills

Where an agent's persona and skills live, how to change them with the file tools, and how project instructions and project skills load.

## Persona

An agent's persona is its own part of the system prompt: first the inline texts of `agent.agents.<id>.instructions`,
then its instruction files. When `agent.agents.<id>.instructionFiles` lists none, the one file is `PERSONA.md` in the
agent's persona directory, `agents/<agent id>/` beside the config file (so `<config dir>/agents/<agent id>/PERSONA.md`;
`alexandrite config path` or config.get tells where the config file is). The directory and the file are created when
first written.

`instructionFiles` takes paths, absolute or relative to the config file's directory, or objects
`{"file": "…", "when": ["group", "heartbeat"]}` whose file loads only in those chat kinds (`direct`, `group`,
`broadcast`) and turn kinds (`message`, `heartbeat`, `reminder`, `job`, `delegated`, `agent_message` …). A file is read
again when it changes, so an edit shows from the next turn on; the prompt takes at most `agent.persona.maxFileChars`
(20000) characters of a file and `agent.persona.maxTotalChars` (60000) in all.

## Editing your own persona

Change the persona file with fs.read, then fs.edit (or fs.write for a whole new text). A write to your own persona
directory is a persistent_state call: on an operator's turn in a clean conversation it runs without asking (in `plan`
it is refused); a guest's or automated turn needs an approval, and in a tainted conversation a person decides. Other
agents' persona directories, shared instruction files and the config's `instructions` stay the operator's: change
them only when asked.

## Skills

A skill is a directory holding `SKILL.md`: YAML front matter with `name` (the directory's name: lowercase letters,
digits and hyphens) and `description` (what it is for, at most 1024 characters), then the instructions as the body,
64 KiB at most. Other files of the skill may sit beside it. The Skills section of the system prompt lists the skills you
may load; skill.load loads one by name, and a message that starts with `/<skill name>` asks for it.

Where skills come from, a later one replacing an earlier one of the same name:

1. Plugins.
2. `<config dir>/skills/<name>/`, for every agent.
3. `<config dir>/agents/<agent id>/skills/<name>/`, for that agent only.
4. Project skills: `.agents/skills/<name>/` and `.claude/skills/<name>/` of a project folder that the conversation
   reached with its tools. A project skill of a trusted folder loads as instructions; one of a folder nobody trusts
   loads as data and taints the conversation.

To install a skill, put its directory into one of the host's skill directories (fs.write for each file, or
command.exec to copy or unpack it); to remove one, delete its directory. Changes show from the next turn. Skill
directories are instruction places: writes there are never cleared by `allowedit` or allow rules, and in a tainted
conversation a person decides.

## Project instructions

When your file tools reach a folder that the operator trusts (`agent.trustedFolders` or `/trust <folder>`), its
`AGENTS.md` and `CLAUDE.md` load once per conversation as a separate message with an [alexandrite:project] header, after
the round's tool results: follow them for work in that project. For a folder nobody trusts nothing loads; the chat is
told it can trust the folder with `/trust <folder>`.
