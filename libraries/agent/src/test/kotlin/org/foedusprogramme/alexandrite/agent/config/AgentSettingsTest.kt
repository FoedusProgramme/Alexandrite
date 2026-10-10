package org.foedusprogramme.alexandrite.agent.config

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.assertStartFails
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.TEST_INSTANCE
import org.junit.jupiter.api.io.TempDir
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
    fun `the agent section decodes every key with its default`() {
        lateinit var settings: AgentSettings

        agentHarness().execute { settings = get() }

        assertEquals(
            listOf<Any>(4, 20, 10, 4, 30L, 10L, 3L, 3, 60L, 300L, 750L, 20_000, 60_000, 20_000_000L),
            with(settings) {
                listOf(
                    maxConcurrentTurns, queue.perKey, queue.commandsPerChat, queue.commandsRunning,
                    queue.commandGateSeconds, shutdown.turnGraceSeconds, shutdown.cancelJoinSeconds, models.retries,
                    models.maxRetryAfterSeconds, models.listingTtlSeconds, previewIntervalMillis,
                    persona.maxFileChars, persona.maxTotalChars, maxMediaBytes,
                )
            },
        )
        assertEquals(emptyList(), settings.trustedFolders)
        assertEquals(emptyMap(), settings.agents)
    }

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
                  "toolTimeoutSeconds": 30,
                  "tools": {"allow": ["fs.*"], "deny": ["fs.write"], "forMembers": ["fs.read"]},
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
            listOf("coder", "Coder", "Reads code.", listOf("Be careful."), 16000, 0.5, 10, 30, "coder-files"),
            listOf(
                agent.id.value, agent.name, coder.description, coder.instructions, coder.maxOutputTokens,
                coder.temperature, coder.maxRounds, coder.toolTimeoutSeconds, coder.workspace,
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
            listOf(tools.allow, tools.deny, tools.forMembers),
        )
        val attachment = agent.attachments.single()
        assertEquals(
            listOf("test:main", "true", "test:main:42#7"),
            listOf("${attachment.instance}", "${attachment.default}", "${attachment.home}"),
        )
    }

    @Test
    fun `an agent without a name is called by its id and selects every tool`() {
        lateinit var agent: Agent

        agentHarness(agents(""""helper": {}""")).execute { agent = get<AgentDirectory>().agents.single() }

        assertEquals("helper", agent.name)
        val tools = agent.config.tools
        assertEquals(listOf(listOf("*"), emptyList(), emptyList()), listOf(tools.allow, tools.deny, tools.forMembers))
        assertEquals(emptyList(), agent.attachments)
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
    fun `a model, reasoning effort or language that does not parse fails the start`() {
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
    }

    @Test
    fun `a number out of its range fails the start`() {
        val cases = mapOf(
            """{"maxConcurrentTurns": 0}""" to "maxConcurrentTurns must be positive",
            """{"previewIntervalMillis": -1}""" to "previewIntervalMillis may not be negative",
            """{"maxMediaBytes": 0}""" to "maxMediaBytes must be positive",
            """{"queue": {"perKey": 0}}""" to "queue.perKey must be positive",
            """{"queue": {"commandsPerChat": 0}}""" to "queue.commandsPerChat must be positive",
            """{"queue": {"commandsRunning": 0}}""" to "queue.commandsRunning must be positive",
            """{"queue": {"commandGateSeconds": 0}}""" to "queue.commandGateSeconds must be positive",
            """{"shutdown": {"turnGraceSeconds": -1}}""" to "shutdown.turnGraceSeconds may not be negative",
            """{"shutdown": {"cancelJoinSeconds": -1}}""" to "shutdown.cancelJoinSeconds may not be negative",
            """{"models": {"retries": -1}}""" to "models.retries may not be negative",
            """{"models": {"maxRetryAfterSeconds": -1}}""" to "models.maxRetryAfterSeconds may not be negative",
            """{"models": {"listingTtlSeconds": -1}}""" to "models.listingTtlSeconds may not be negative",
            """{"persona": {"maxFileChars": 0}}""" to "persona.maxFileChars must be positive",
            """{"persona": {"maxTotalChars": 0}}""" to "persona.maxTotalChars must be positive",
            agents(""""coder": {"maxOutputTokens": 0}""") to "agents.coder.maxOutputTokens must be positive",
            agents(""""coder": {"temperature": -0.5}""") to "agents.coder.temperature must be a number of at least 0",
            agents(""""coder": {"maxRounds": 0}""") to "agents.coder.maxRounds must be positive",
            agents(""""coder": {"toolTimeoutSeconds": 0}""") to "agents.coder.toolTimeoutSeconds must be positive",
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
            agents(""""coder": {"tools": {"forMembers": ["fs.read."]}}"""),
            "agents.coder.tools.forMembers $message",
        )
    }

    @Test
    fun `a lone agent attached to an instance needs no default flag`() {
        lateinit var directory: AgentDirectory

        agentHarness(agents(""""coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}""")) {
            channel().model(ScriptedModel())
        }.execute { directory = get() }

        assertEquals("coder", directory.default(TEST_INSTANCE)?.id?.value)
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
    fun `linked chats decode as groups of chat addresses`() {
        val config = agents(
            """
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}, "test:work": {}},
              "linkedChats": [["test:main:1", "test:work:2#7"], ["test:main:3", "test:main:3#1", "test:work:4"]]
            }
            """,
        )
        lateinit var agent: Agent

        agentHarness(config) { channel().channel("work").model(ScriptedModel()) }
            .execute { agent = get<AgentDirectory>().agents.single() }

        assertEquals(
            listOf(listOf("test:main:1", "test:work:2#7"), listOf("test:main:3", "test:main:3#1", "test:work:4")),
            agent.linkedChats.map { group -> group.map { it.toString() } },
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
    fun `workspaces, trusted folders and instruction files are paths that are not blank`() {
        assertConfigFails(
            agents(""""coder": {"workspace": " "}"""),
            "agents.coder.workspace is a blank or malformed path",
        )
        assertConfigFails("""{"trustedFolders": ["/srv", ""]}""", "trustedFolders holds a blank or malformed path")
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
                "agent_message",
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
    fun `a key that no agent setting reads fails the start`() {
        assertConfigFails(
            agents(""""coder": {"modle": "scripted/m"}"""),
            "Encountered an unknown key 'modle' at path: $",
        )
    }
}
