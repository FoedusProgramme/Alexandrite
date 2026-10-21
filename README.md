## Alexandrite

### Maven artifacts

Every module under `libraries/` is published to Maven Central in the group `org.unitedsoftware.alexandrite`, and
`alexandrite-bom` aligns their versions.

For plugin authors:

- `alexandrite-plugin-sdk`: the plugin SDK, the only stable API. From 1.0 it follows semantic versioning.
- `alexandrite-testkit`: test doubles and a harness that runs a plugin in a runtime of its own.
- `alexandrite-ksp` and the Gradle plugin `org.unitedsoftware.alexandrite.plugin`: build a plugin.

For hosts that embed Alexandrite, the implementation modules: `alexandrite-runtime`, `alexandrite-internal`,
`alexandrite-agent`, `alexandrite-tools`, `alexandrite-commands`, `alexandrite-mcp`, `alexandrite-channel-common`,
`alexandrite-channel-telegram`, `alexandrite-provider-common`, `alexandrite-provider-anthropic-compatible`,
`alexandrite-provider-deepseek`, `alexandrite-provider-lmstudio`, `alexandrite-provider-openai-compatible`,
`alexandrite-provider-openai-responses`, `alexandrite-provider-openrouter`, `alexandrite-store-sqlite`,
`alexandrite-automation-common`, `alexandrite-automation-heartbeat`, `alexandrite-automation-schedule`,
`alexandrite-sandbox-bubblewrap` and `alexandrite-sandbox-seatbelt`. Their API may change in any release, so a plugin
depends on the SDK only.
