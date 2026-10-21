# Changelog

## 0.1.0-alpha.1

The first public build. Alexandrite is a coding-focused agent framework on the JVM, with a plugin SDK; expect breaking
changes until 1.0, from which the SDK follows semantic versioning.

- **Install and run:** a one-line installer (`install.sh`, `install.ps1`) with a JRE on demand, `alexandrite init`
  (model, Telegram bot, admin claim, language and zone, background service on systemd, launchd or Windows), and the
  `alexandrite` command (service control, `doctor`, `update` with rollback, `config`, `secret`, shortcuts).
- **Agents:** several agents with equal models across providers (DeepSeek, OpenRouter, LM Studio, OpenAI-compatible,
  OpenAI Responses, Anthropic-compatible), compaction, jobs and delegation, profiles, skills, project instructions,
  `history.search`, usage and budgets, scheduled tasks and heartbeats, and no silent turns.
- **Tools:** `fs.*`, `command.exec` (shell chains and pipelines run as their own steps), `web.fetch` / `web.search`,
  `chat.send` with replies to a given message, and MCP servers over stdio and HTTP with OAuth.
- **Permission layer:** modes from `plan` to `bypass`, allow and deny rules, a side-model judge, a configurable hard
  floor, sandboxes (Seatbelt on macOS, bubblewrap on Linux), and taint tracking that asks before untrusted content
  enters a clean conversation, with trusted folders, domains, programs, servers and people.
- **Channels:** Telegram with pairing, approvals by button, streaming replies and Chinese and English texts.
- **For plugin authors:** the plugin SDK, a KSP processor, a testkit, a Gradle plugin
  (`org.unitedsoftware.alexandrite.plugin`) and a BOM on Maven Central.
