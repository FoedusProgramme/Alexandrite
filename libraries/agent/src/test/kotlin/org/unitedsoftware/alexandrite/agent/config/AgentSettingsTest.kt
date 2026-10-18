package org.unitedsoftware.alexandrite.agent.config

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.assertStartFails
import org.unitedsoftware.alexandrite.agent.context.Limits
import org.unitedsoftware.alexandrite.agent.context.limits
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentSettingsTest {
    @TempDir
    lateinit var directory: Path

    private fun assertConfigFails(config: String, message: String) {
        agentHarness(config).assertStartFails(StartStage.CONFIG, "Invalid config at 'agent': $message")
    }

    private fun agents(agents: String) = """{"agents": {$agents}}"""

    @Test
    fun `an agent decodes as configured`() {
        val persona = directory.resolve("coder.md").apply { writeText("Hello.") }
        directory.resolve("notes.md").writeText("Notes.")
        val config = """
            {
              "trustedFolders": ["/srv/repo"],
              "agents": {
                "coder": {
                  "name": "Coder",
                  "description": "Reads code.",
                  "instructions": ["Be careful."],
                  "instructionFiles": ["$persona", {"file": "notes.md", "when": ["direct", "heartbeat"]}],
                  "model": "scripted/test-model",
                  "reasoning": "medium",
                  "language": "zh_CN",
                  "maxOutputTokens": 16000,
                  "temperature": 0.5,
                  "maxRounds": 10,
                  "maxTurnTokens": 500000,
                  "toolTimeoutSeconds": 30,
                  "foregroundSeconds": 20,
                  "tools": {"allow": ["fs.*"], "deny": ["fs.write"], "forGuests": ["fs.read"]},
                  "permission": {"allow": ["command.exec(git status)"], "deny": ["command.exec(./gradlew test)"]},
                  "workspace": "coder-files",
                  "channels": {"test:main": {"default": true, "home": "42#7"}}
                }
              }
            }
        """.trimIndent()
        lateinit var agent: Agent

        agentHarness(config) { configFile(directory.resolve("alexandrite.json")).channel().model(ScriptedModel()) }
            .execute { agent = get<AgentDirectory>().agents.single() }

        val coder = agent.config
        assertEquals(
            listOf(
                "coder", "Coder", "Reads code.",
                listOf(
                    "Be careful.",
                ),
                16000, 0.5, 10, 500000L, 30, 20, "coder-files",
            ),
            listOf(
                agent.id.value, agent.name, coder.description, coder.instructions, coder.maxOutputTokens,
                coder.temperature, coder.maxRounds, coder.maxTurnTokens, coder.toolTimeoutSeconds,
                coder.foregroundSeconds, coder.workspace,
            ),
        )
        assertEquals(
            listOf("$persona" to emptyList(), "notes.md" to listOf("direct", "heartbeat")),
            coder.instructionFiles.map { it.file to it.`when` },
        )
        assertEquals(ModelRef.parse("scripted/test-model"), agent.model)
        assertEquals(ReasoningEffort.MEDIUM, agent.reasoning)
        assertEquals(LanguageTag("zh-CN"), agent.language)
        val tools = coder.tools
        assertEquals(
            listOf(listOf("fs.*"), listOf("fs.write"), listOf("fs.read")),
            listOf(tools.allow, tools.deny, tools.forGuests),
        )
        assertEquals(
            listOf(listOf("command.exec(git status)"), listOf("command.exec(./gradlew test)")),
            listOf(coder.permission.allow, coder.permission.deny),
        )
        val attachment = agent.attachments.single()
        assertEquals(
            listOf("test:main", "true", "test:main:42#7"),
            listOf("${attachment.instance}", "${attachment.default}", "${attachment.home}"),
        )
    }

    @Test
    fun `the tools for guests are still read from their former key`() {
        val settings = settings(agents(""""coder": {"description": "Codes.", "tools": {"forMembers": ["fs.read"]}}"""))

        assertEquals(listOf("fs.read"), settings.agents.getValue("coder").tools.forGuests)
    }

    @Test
    fun `an agent sets no round limit or token budget unless configured, and backgrounds a call after five minutes`() {
        val settings = settings(agents(""""coder": {"description": "Codes."}"""))
        val coder = settings.agents.getValue("coder")

        assertEquals(
            listOf(null, null, 1800, 300, 4),
            listOf(
                coder.maxRounds,
                coder.maxTurnTokens,
                coder.toolTimeoutSeconds,
                coder.foregroundSeconds,
                settings.maxJobsPerChat,
            ),
        )
    }

    @Test
    fun `an agent compacts from 95 percent of the window down to 20, and clears tool results from 60`() {
        val defaults = settings(agents(""""coder": {"description": "Codes."}""")).agents.getValue("coder").compaction
        val compaction = """{"trigger": 150000, "target": 0.1, "clearAt": "off", "clearAtLeast": 2000, """ +
            """"keepToolRounds": 3, "keepTurns": 1, "maxSummaryTokens": 2000, "model": "scripted/fast"}"""
        val set = settings(agents(""""coder": {"description": "Codes.", "compaction": $compaction}"""))
            .agents.getValue("coder").compaction

        assertEquals(listOf(190_000L, 40_000L, 120_000L, 10_000L), defaults.limits(200_000).values())
        assertEquals(
            listOf(5, 2, 4000, null),
            listOf(defaults.keepToolRounds, defaults.keepTurns, defaults.maxSummaryTokens, defaults.model),
        )
        assertEquals(listOf(150_000L, 20_000L, null, 2000L), set.limits(200_000).values())
        assertEquals(listOf(100_000L, 10_000L, null, 2000L), set.limits(100_000).values())
        assertEquals(
            listOf(3, 1, 2000, "scripted/fast"),
            listOf(set.keepToolRounds, set.keepTurns, set.maxSummaryTokens, set.model),
        )
    }

    private fun Limits.values(): List<Long?> = listOf(trigger, target, clearAt, clearAtLeast)

    @Test
    fun `an answer reserve that lowers the trigger lowers the target in the same proportion`() {
        val compaction = """{"trigger": 0.6, "target": 0.5}"""
        val set = settings(agents(""""coder": {"description": "Codes.", "compaction": $compaction}"""))
            .agents.getValue("coder").compaction

        assertEquals(3072L to 2560L, set.limits(8192, 4096).let { it.trigger to it.target })
        assertEquals(4915L to 4096L, set.limits(8192, 1024).let { it.trigger to it.target })
    }

    @Test
    fun `a compaction threshold that is neither a fraction nor tokens, or a target at its trigger, fails the start`() {
        val threshold = "must be a fraction of the context window above 0 and at most 1, such as 0.6, or a whole " +
            "number of tokens"
        val cases = mapOf(
            """{"trigger": 0}""" to "trigger $threshold",
            """{"trigger": "off"}""" to "trigger $threshold",
            """{"target": 1.5}""" to "target $threshold",
            """{"clearAtLeast": "half"}""" to "clearAtLeast $threshold",
            """{"clearAt": -1}""" to "clearAt $threshold, or \"off\"",
            """{"target": 0.95}""" to "target must be below agents.coder.compaction.trigger",
            """{"trigger": 50000, "target": 50000}""" to "target must be below agents.coder.compaction.trigger",
            """{"keepToolRounds": -1}""" to "keepToolRounds may not be negative",
            """{"keepTurns": -1}""" to "keepTurns may not be negative",
            """{"maxSummaryTokens": 0}""" to "maxSummaryTokens must be positive",
            """{"model": "fast"}""" to
                "model is no model reference: write <endpoint>/<model>, such as \"anthropic/claude-sonnet-5-5\"",
        )

        for ((compaction, message) in cases) {
            assertConfigFails(agents(""""coder": {"compaction": $compaction}"""), "agents.coder.compaction.$message")
        }
    }

    @Test
    fun `an agent id outside the id grammar fails the start`() {
        assertConfigFails(
            agents(""""Coder": {}"""),
            "agents.Coder is no agent id: an id is lowercase words of letters and digits, each starting with a " +
                "letter, joined by single hyphens, such as \"coder\"",
        )
    }

    @Test
    fun `an attachment key that is no channel instance fails the start`() {
        assertConfigFails(
            agents(""""coder": {"model": "scripted/m", "channels": {"telegram": {"default": true}}}"""),
            "agents.coder.channels.telegram is no channel instance: write <type>:<name>, such as \"telegram:work\"",
        )
    }

    @Test
    fun `a home that is no chat of its instance fails the start`() {
        assertConfigFails(
            agents(""""coder": {"model": "scripted/m", "channels": {"test:main": {"default": true, "home": ""}}}"""),
            "agents.coder.channels.test:main.home is no chat of the instance: write its chat id, and #<thread> for a " +
                "thread",
        )
    }

    @Test
    fun `a model, reasoning effort, language or zone that does not parse fails the start`() {
        assertConfigFails(
            agents(""""coder": {"model": "sonnet"}"""),
            "agents.coder.model is no model reference: write <endpoint>/<model>, such as " +
                "\"anthropic/claude-sonnet-5-5\"",
        )
        assertConfigFails(
            agents(""""coder": {"reasoning": "extreme"}"""),
            "agents.coder.reasoning must be one of none, minimal, low, medium, high, xhigh, max",
        )
        assertConfigFails(
            agents(""""coder": {"language": "no tag"}"""),
            "agents.coder.language is no BCP 47 language tag, such as \"zh-CN\"",
        )
        assertConfigFails(
            agents(""""coder": {"zone": "Mars/Olympus"}"""),
            "agents.coder.zone is no time zone: write a region id such as \"Asia/Shanghai\" or an offset such as " +
                "\"+08:00\"",
        )
    }

    @Test
    fun `models beside a model, a malformed or repeated model, fail the start, and models serve channels`() {
        assertConfigFails(
            agents(""""coder": {"model": "a/b", "models": ["a/b"], "channels": {"test:main": {}}}"""),
            "agents.coder sets both model and models: name one model, or list them all in models",
        )
        assertConfigFails(
            agents(""""coder": {"models": ["a/b", "sonnet"], "channels": {"test:main": {}}}"""),
            "agents.coder.models[1] is no model reference: write <endpoint>/<model>, such as " +
                "\"anthropic/claude-sonnet-5-5\"",
        )
        assertConfigFails(
            agents(""""coder": {"models": ["a/b", "a/b"], "channels": {"test:main": {}}}"""),
            "agents.coder.models names a model twice",
        )
        assertConfigFails(
            agents(""""coder": {"models": [], "channels": {"test:main": {}}}"""),
            "agents.coder.model is missing: an agent that serves channels needs a model",
        )
    }

    @Test
    fun `a permission mode that is unknown or a bypass fails the start`() {
        for (mode in listOf("yolo", "bypass")) {
            assertConfigFails(
                agents(""""coder": {"permission": {"mode": "$mode"}}"""),
                "agents.coder.permission.mode must be one of plan, manual, ask, allowedit, auto: a bypass is set " +
                    "per chat, for a limited time",
            )
        }
    }

    @Test
    fun `a permission rule of unknown syntax fails the start, named by its place`() {
        assertConfigFails(
            agents(""""coder": {"permission": {"allow": ["fs.read", "fs.write(notes.md)"]}}"""),
            "agents.coder.permission.allow[1] gives an argument prefix to a tool other than command.exec: write the " +
                "tool alone",
        )
        assertConfigFails(
            agents(""""coder": {"permission": {"deny": ["command.exec(git push | tee log)"]}}"""),
            "agents.coder.permission.deny[0] holds shell syntax, which no call of command.exec runs: write a " +
                "program and its first arguments",
        )
        assertConfigFails(
            agents(""""coder": {"permission": {"deny": ["make install"]}}"""),
            "agents.coder.permission.deny[0] is no rule: write a tool name, or command.exec with the start of a " +
                "command line in parentheses, such as \"command.exec(git push)\"",
        )
    }

    @Test
    fun `a trust rule may be a glob of tool names or name a program by its path, and fails the start otherwise`() {
        assertConfigFails(
            agents(""""coder": {"permission": {"trust": ["web.fetch", "git log"]}}"""),
            "agents.coder.permission.trust[1] is no rule: write a tool name, or command.exec with the start of a " +
                "command line in parentheses, such as \"command.exec(git push)\"",
        )
        val trusted = """"coder": {"description": "Codes.", "permission": {"trust": """ +
            """["mcp.files.*", "command.exec(./gradlew)", "command.exec(git)"]}}"""
        agentHarness(agents(trusted)).execute {}
    }

    @Test
    fun `a declared allow rule that names a program by its path fails the start, a deny rule may`() {
        val named = "names a program by its path: declare a program by its bare name, such as \"git status\"; " +
            "allow a script per session through the approval's \"Always allow\", which binds its real path"
        for (program in listOf("./gradlew test", "bin/x", "/usr/bin/git status", "~/x")) {
            assertConfigFails(
                agents(""""coder": {"permission": {"allow": ["command.exec($program)"]}}"""),
                "agents.coder.permission.allow[0] $named",
            )
        }
        assertConfigFails(
            agents(""""coder": {"permission": {"allow": ["fs.read", "command.exec(./gradlew test)"]}}"""),
            "agents.coder.permission.allow[1] $named",
        )

        val denied =
            """"coder": {"description": "Codes.", "permission": {"deny": ["command.exec(./gradlew test)"]}}"""
        agentHarness(agents(denied)).execute {}
    }

    @Test
    fun `a judge that is no model reference or a judge timeout or length that is not positive fails the start`() {
        assertConfigFails(
            agents(""""coder": {"permission": {"judge": "haiku"}}"""),
            "agents.coder.permission.judge is no model reference: write <endpoint>/<model>, such as " +
                "\"anthropic/claude-haiku-5-5\"",
        )
        assertConfigFails(
            agents(""""coder": {"permission": {"judgeTimeoutSeconds": 0}}"""),
            "agents.coder.permission.judgeTimeoutSeconds must be positive",
        )
        assertConfigFails(
            agents(""""coder": {"permission": {"judgeMaxChars": 0}}"""),
            "agents.coder.permission.judgeMaxChars must be positive",
        )
    }

    @Test
    fun `a number out of its range fails the start`() {
        val cases = mapOf(
            """{"maxConcurrentTurns": 0}""" to "maxConcurrentTurns must be positive",
            """{"previewIntervalMillis": -1}""" to "previewIntervalMillis may not be negative",
            """{"maxMediaBytes": 0}""" to "maxMediaBytes must be positive",
            """{"queue": {"perKey": 0}}""" to "queue.perKey must be positive",
            """{"queue": {"commandsPerChat": 0}}""" to "queue.commandsPerChat must be positive",
            """{"queue": {"commandGateSeconds": 0}}""" to "queue.commandGateSeconds must be positive",
            """{"shutdown": {"turnGraceSeconds": -1}}""" to "shutdown.turnGraceSeconds may not be negative",
            """{"shutdown": {"cancelJoinSeconds": -1}}""" to "shutdown.cancelJoinSeconds may not be negative",
            """{"models": {"retries": -1}}""" to "models.retries may not be negative",
            """{"models": {"maxRetryAfterSeconds": -1}}""" to "models.maxRetryAfterSeconds may not be negative",
            """{"models": {"listingTtlSeconds": -1}}""" to "models.listingTtlSeconds may not be negative",
            """{"persona": {"maxFileChars": 0}}""" to "persona.maxFileChars must be positive",
            """{"persona": {"maxTotalChars": 0}}""" to "persona.maxTotalChars must be positive",
            """{"conversationDirectory": {"maxBytes": 0}}""" to "conversationDirectory.maxBytes must be positive",
            """{"conversationDirectory": {"keepDays": 0}}""" to "conversationDirectory.keepDays must be positive",
            agents(""""coder": {"maxOutputTokens": 0}""") to "agents.coder.maxOutputTokens must be positive",
            agents(""""coder": {"temperature": -0.5}""") to "agents.coder.temperature must be a number of at least 0",
            agents(""""coder": {"maxRounds": 0}""") to "agents.coder.maxRounds must be positive",
            agents(""""coder": {"maxTurnTokens": 0}""") to "agents.coder.maxTurnTokens must be positive",
            agents(""""coder": {"toolTimeoutSeconds": 0}""") to "agents.coder.toolTimeoutSeconds must be positive",
            agents(""""coder": {"foregroundSeconds": 0}""") to "agents.coder.foregroundSeconds must be positive",
            """{"maxJobsPerChat": 0}""" to "maxJobsPerChat must be positive",
        )

        for ((config, message) in cases) assertConfigFails(config, message)
    }

    @Test
    fun `a malformed tool glob fails the start`() {
        val message =
            "holds a malformed tool glob: write dotted tool names, with * for any characters, such as \"fs.*\""

        assertConfigFails(agents(""""coder": {"tools": {"allow": ["Fs.*"]}}"""), "agents.coder.tools.allow $message")
        assertConfigFails(agents(""""coder": {"tools": {"deny": ["fs..read"]}}"""), "agents.coder.tools.deny $message")
        assertConfigFails(
            agents(""""coder": {"tools": {"forGuests": ["fs.read."]}}"""),
            "agents.coder.tools.forGuests $message",
        )
    }

    @Test
    fun `each instance with several attached agents has exactly one default agent`() {
        assertConfigFails(
            agents(
                """
                "coder": {"model": "scripted/m", "channels": {"test:main": {}}},
                "helper": {"model": "scripted/m", "channels": {"test:main": {}}}
                """,
            ),
            "agents attached to test:main name no default agent: set \"default\": true in channels.test:main of " +
                "exactly one of coder, helper",
        )
        assertConfigFails(
            agents(
                """
                "coder": {"model": "scripted/m", "channels": {"test:main": {"default": true}}},
                "helper": {"model": "scripted/m", "channels": {"test:main": {"default": true}}}
                """,
            ),
            "agents coder, helper are each the default agent of test:main: keep \"default\": true in " +
                "channels.test:main of one of them",
        )
    }

    @Test
    fun `a linked group holds at least two distinct chats of attached instances`() {
        fun links(groups: String) =
            agents(""""coder": {"model": "scripted/m", "channels": {"test:main": {}}, "linkedChats": $groups}""")

        assertConfigFails(links("""[["test:main:1"]]"""), "agents.coder.linkedChats[0] holds fewer than two chats")
        assertConfigFails(
            links("""[["test:main:1", "test:main:2"], ["test:main:3", "test:main"]]"""),
            "agents.coder.linkedChats[1][1] is no chat address: write <type>:<name>:<chat>, such as " +
                "\"telegram:work:123456\"",
        )
        assertConfigFails(
            links("""[["test:main:1", "test:work:2"]]"""),
            "agents.coder.linkedChats[0][1] is a chat of test:work, which the agent is not attached to: add it to " +
                "agents.coder.channels",
        )
        assertConfigFails(
            links("""[["test:main:1", "test:main:2"], ["test:main:3", "test:main:1"]]"""),
            "agents.coder.linkedChats[1][1] is the chat of linkedChats[0][0] again: a chat is in one group of an agent",
        )
        assertConfigFails(
            links("""[["test:main:1", "test:main:1#2", "test:main:1"]]"""),
            "agents.coder.linkedChats[0][2] is the chat of linkedChats[0][0] again: a chat is in one group of an agent",
        )
    }

    @Test
    fun `a home chat claimed by two agents fails the start`() {
        assertConfigFails(
            agents(
                """
                "coder": {"model": "scripted/m", "channels": {"test:main": {"default": true, "home": "42"}}},
                "helper": {"model": "scripted/m", "channels": {"test:main": {"home": "42"}}}
                """,
            ),
            "agents.helper.channels.test:main.home names the home chat of agent coder: a chat is the home of one agent",
        )
    }

    @Test
    fun `an agent that serves channels needs a model`() {
        assertConfigFails(
            agents(""""coder": {"channels": {"test:main": {"default": true}}}"""),
            "agents.coder.model is missing: an agent that serves channels needs a model",
        )
    }

    @Test
    fun `an agent without channels is a profile, and needs a description`() {
        assertConfigFails(
            agents(""""coder": {}"""),
            "agents.coder.description is missing: an agent that serves no channels is a profile, which needs one",
        )
        settings(agents(""""coder": {"description": "Codes."}"""))
    }

    @Test
    fun `workspaces, trusted folders, protected paths and instruction files are paths that are not blank`() {
        assertConfigFails(
            agents(""""coder": {"workspace": " "}"""),
            "agents.coder.workspace is a blank or malformed path",
        )
        assertConfigFails("""{"trustedFolders": ["/srv", ""]}""", "trustedFolders holds a blank or malformed path")
        assertConfigFails(
            """{"floor": {"deny": [" "]}}""",
            "floor.deny[0] is neither a group (credentials, system, alexandrite) nor a path",
        )
        assertConfigFails(
            agents(""""coder": {"instructionFiles": [""]}"""),
            "agents.coder.instructionFiles holds a blank or malformed path",
        )
    }

    @Test
    fun `an instruction file's conditions name chat kinds and turn kinds`() {
        assertConfigFails(
            agents(""""coder": {"instructionFiles": [{"file": "me.md", "when": ["private", "direct"]}]}"""),
            "agents.coder.instructionFiles names \"private\" in \"when\", which is no chat kind or turn kind: use " +
                "direct, group, broadcast, unknown, message, command, heartbeat, reminder, approval, delegated, " +
                "agent_message, job",
        )
    }

    @Test
    fun `an instruction file entry is a path or an object`() {
        assertConfigFails(
            agents(""""coder": {"instructionFiles": [1]}"""),
            "An instructionFiles entry is a path or an object with \"file\" and \"when\".",
        )
        assertConfigFails(
            agents(""""coder": {"instructionFiles": [{"path": "me.md"}]}"""),
            "Encountered an unknown key 'path' at path: $",
        )
    }

    @Test
    fun `an agent name is one line of text and inline instructions are not blank`() {
        assertConfigFails(agents(""""coder": {"name": "Co\nder"}"""), "agents.coder.name must be one line of text")
        assertConfigFails(
            agents(""""coder": {"instructions": ["Hi", " "]}"""),
            "agents.coder.instructions holds a blank entry",
        )
    }

    @Test
    fun `the queue reads no commandsRunning`() {
        assertConfigFails(
            """{"queue": {"commandsRunning": 4}}""",
            "Encountered an unknown key 'commandsRunning' at path: $",
        )
    }

    @Test
    fun `a key that no agent setting reads fails the start`() {
        assertConfigFails(
            agents(""""coder": {"modle": "scripted/m"}"""),
            "Encountered an unknown key 'modle' at path: $",
        )
    }
}
