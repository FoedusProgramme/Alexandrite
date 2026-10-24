# Changelog

## 1.0.0-alpha.2

- Config keys with dots, like model names, are written in brackets: `models["gpt-6.1-sol"]`.
- A model with an unknown context window is managed as if it had 128k tokens.
- A large request, or an answer that breaks off, is retried once.
- Proxies no longer count streamed answers as failed.
- `alexandrite uninstall` also removes the PATH lines the install script added.

## 1.0.0-alpha.1

- Publish every library module on Maven Central.
- `alexandrite config` opens an interactive menu on a terminal.
- Actually make Windows usable.
- Folders are trusted per agent, and agents' messages act for the person who started them.
- Breaking SDK changes: approval answers, untaint results, turn phases and trust control.

## 0.1.0-alpha.1

The first public build. Alexandrite is an agent harness framework on the JVM. Expect breaking changes until 1.0, from
which the SDK follows semantic versioning.
Plugin SDK, KSP processor, testkit, Gradle plugin and BOM should be available on Maven Central shortly after release.
