package org.unitedsoftware.alexandrite.agent.hostconfig

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettings
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ConfigToolsTest {
    @TempDir
    lateinit var directory: Path

    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val judge = ScriptedModel(EndpointId("judge"))
    private val root: Path get() = directory.toRealPath()
    private val configFile: Path get() = root.resolve("config/alexandrite.json")
    private val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE) { _, _ -> ToolResult("Written.") }

    @Serializable
    class Vault(val name: String = "", val token: Secret? = null, val key: Secret? = null)

    private val vault = object : PluginIndex {
        override val info = PluginInfo("vault", "Vault", "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "t.Vault")
        override val configRoot = PluginIds.thirdPartyRoot("vault")

        override fun bindings(): List<Binding<*>> = emptyList()

        override fun configSections(): List<ConfigSectionSpec<*>> =
            listOf(ConfigSectionSpec(key<Vault>(), "", Vault.serializer(), "Vault"))
    }

    private fun harness(): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}},
              "permission": {"judge": "judge/test-model"}
            }
          }
        }
        """.trimIndent(),
        store,
    ) {
        channel(operators = setOf("operator")).model(model).model(judge).tool(write)
        plugin(vault).config(vault, """{"name": "main", "token": "s3cret-value", "key": "${'$'}{env:VAULT_KEY}"}""")
        environment(mapOf("VAULT_KEY" to "k3y-value"))
        configFile(configFile).dataRoot(root.resolve("data"))
    }

    private suspend fun PluginHarness.Running.ask() {
        val sender = channel().user("operator")
        (channel().receive("Change it.", sender = sender) as Admission.Accepted).ticket.outcome()
    }

    private suspend fun PluginHarness.Running.setMode(mode: PermissionMode) = get<ChatSettings>().update(
        channel().chat(),
        ChatSettingsUpdate.builder()
            .mode(mode, Instant.now().plus(1, ChronoUnit.HOURS).takeIf { mode == PermissionMode.BYPASS })
            .build(),
        channel().user("operator"),
    )

    private suspend fun results(): List<ToolResultEntry> {
        val conversation = store.conversations.current(AgentChatKey.parse("coder@test:main:chat")).id
        return store.transcripts.entries(conversation).filterIsInstance<ToolResultEntry>()
    }

    private fun ToolResultEntry.text(): String = content.joinToString("") { (it as TextPart).text }

    private fun set(path: String, value: String) = """{"path": "$path", "value": $value}"""

    @Test
    fun `config get shows a value with each secret as its reference or hidden, never as its value`() {
        harness().execute {
            val get = checkNotNull(get<ToolRegistry>().tool("config.get"))
            val context = testToolContext()

            val whole = get.execute(JsonObject(mapOf("path" to JsonPrimitive("plugins.vault"))), context)
            val token = get.execute(JsonObject(mapOf("path" to JsonPrimitive("plugins.vault.token"))), context)
            val file = get.execute(JsonObject(emptyMap()), context)

            assertEquals(
                "plugins.vault = {\n    \"name\": \"main\",\n    \"token\": \"[secret]\",\n    \"key\": " +
                    "\"\${env:VAULT_KEY}\"\n}",
                (whole.content.single() as TextPart).text,
            )
            assertEquals("plugins.vault.token = \"[secret]\"", (token.content.single() as TextPart).text)
            val text = (file.content.single() as TextPart).text
            assertEquals(listOf(false, false), listOf("s3cret-value" in text, "k3y-value" in text))
        }
    }

    @Test
    fun `config set is gated by the chat's mode, and allowedit does not clear it`() {
        val path = "agent.maxConcurrentTurns"
        for (value in listOf(2, 2, 2, 3, 4)) {
            model.reply { toolCall("config.set", set(path, "$value")) }.reply { text("Ok.") }
        }
        judge.reply { text("""{"verdict":"allow","reason":"asked for"}""") }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(ApprovalAnswer.Denied(operator), ApprovalAnswer.Allowed(operator))
            for (mode in listOf("plan", "allowedit", "ask", "auto", "bypass").map(PermissionMode::of)) {
                setMode(mode)
                ask()
            }

            val restart = "Takes effect once an operator restarts Alexandrite: $path."
            assertEquals(
                listOf(
                    ToolOutcome.NotRun(NotRunReason.PLAN_MODE),
                    ToolOutcome.NotRun(NotRunReason.DENIED, channel().approvals[0].id),
                    ToolOutcome.Succeeded,
                    ToolOutcome.Succeeded,
                    ToolOutcome.Succeeded,
                ),
                results().map { it.outcome },
            )
            assertEquals(List(3) { "Changed $path. $restart" }, results().drop(2).map { it.text() })
            assertEquals(
                List(2) { ReviewPreview.Text("Set $path to 2\n$restart") },
                channel().approvals.map { it.preview },
            )
            assertEquals(
                listOf("the chat is in allowedit mode", "the chat is in ask mode"),
                channel().approvals.map { it.reason },
            )
            assertEquals(1, judge.requests.size)
            assertContains(Files.readString(configFile), "\"maxConcurrentTurns\": 4")
        }
    }

    @Test
    fun `a change of the floor by the agent always asks a person, and takes effect at once once allowed`() {
        val shared = root.resolve("shared")
        val asked = JsonArray(listOf(JsonPrimitive("$shared")))
        model.reply { toolCall("config.set", set("agent.floor.ask", "$asked")) }.reply { text("Ok.") }
            .reply { toolCall("config.unset", """{"path": "agent"}""") }.reply { text("Ok.") }
            .reply { toolCall("config.set", set("agent.agents.coder.description", "\"Codes.\"")) }
            .reply { text("Ok.") }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator), ApprovalAnswer.Denied(operator))
            setMode(PermissionMode.BYPASS)
            repeat(3) { ask() }

            val floor = "the change reaches agent.floor, the places of the hard floor, which only a person may change"
            assertEquals(listOf(floor, floor), channel().approvals.map { it.reason })
            assertEquals(
                listOf(
                    ToolOutcome.Succeeded,
                    ToolOutcome.NotRun(NotRunReason.DENIED, channel().approvals[1].id),
                    ToolOutcome.Succeeded,
                ),
                results().map { it.outcome },
            )
            assertEquals("Changed agent.floor.ask. Takes effect at once: agent.floor.ask.", results()[0].text())
            val notes = shared.resolve("a.txt")
            assertEquals(FloorCheck.Ask(notes, "a place the operator protects"), get<HardFloor>().checkRead(notes))
        }
    }

    @Test
    fun `a secret written as its value is refused before anyone is asked`() {
        model.reply { toolCall("config.set", set("plugins.vault.token", "\"an-actual-token\"")) }
            .reply { text("Ok.") }

        harness().execute {
            ask()

            val result = results().single()
            assertEquals(ToolOutcome.NotRun(NotRunReason.INVALID_ARGUMENTS), result.outcome)
            assertContains(result.text(), "write an \${env:NAME} reference instead")
            assertEquals(emptyList(), channel().approvals)
            assertEquals(false, "an-actual-token" in Files.readString(configFile))
        }
    }
}
