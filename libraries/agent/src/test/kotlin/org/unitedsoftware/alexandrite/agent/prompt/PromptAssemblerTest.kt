package org.unitedsoftware.alexandrite.agent.prompt

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.delivery.DELIVERY_SECTION
import org.unitedsoftware.alexandrite.agent.directory.CONVERSATION_DIRECTORY_SECTION
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.directory.conversationDirectorySection
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.media.MEDIA_SECTION
import org.unitedsoftware.alexandrite.agent.project.PROJECT_SECTION
import org.unitedsoftware.alexandrite.agent.skill.SKILLS_SECTION
import org.unitedsoftware.alexandrite.agent.tool.TOOLS_SECTION
import org.unitedsoftware.alexandrite.agent.workspace.WORKSPACE_SECTION
import org.unitedsoftware.alexandrite.agent.workspace.workspaceSection
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptAssemblerTest {
    @TempDir
    lateinit var directory: Path

    private val config = """
        {
          "agents": {
            "coder": {
              "name": "Coder",
              "description": "Codes.",
              "instructions": ["Be brief."]
            },
            "helper": {
              "description": "Helps.",
              "instructionFiles": [{"file": "private.md", "when": ["direct"]}]
            }
          }
        }
    """.trimIndent()

    private fun write(name: String, text: String, modified: String) {
        val file = directory.resolve(name)
        file.parent.createDirectories()
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(modified)))
    }

    private fun PromptSection.line(): String = "$id: ${if (id == BASE_SECTION) text.substringBefore('.') else text}"

    private fun List<PromptSection>.withoutMedia(): List<PromptSection> = filterNot { it.id == MEDIA_SECTION }

    private fun turn(info: TurnInfo, offered: Set<String> = emptySet()): PromptTurn =
        PromptTurn(info, offered, ScriptedModel.modelInfo())

    @Test
    fun `a turn gets the base section, the agent's instructions and the chat's language, in that order`() {
        write("agents/coder/PERSONA.md", "Read the code first.", "2026-01-01T00:00:00Z")
        write("private.md", "The operator likes tea.", "2026-01-01T00:00:00Z")

        agentHarness(config) { configFile(directory.resolve("alexandrite.json")) }.execute {
            val assembler = get<PromptAssembler>()
            val coder = testTurn { agent(AgentId("coder")).language(LanguageTag("zh-CN")) }
            val helper = testTurn(TurnKind.HEARTBEAT) { agent(AgentId("helper")) }

            val sections = assembler.sections(turn(coder), ChatKind.GROUP)
            assertEquals(
                listOf(
                    "alexandrite.base: You are Coder",
                    "agent.instructions.1: Be brief.",
                    "agent.instructions.2: Read the code first.",
                    "chat.language: Answer in zh-CN unless the user writes in another language.",
                ),
                sections.withoutMedia().map { it.line() },
            )
            assertEquals(true, sections.all { it.id == MEDIA_SECTION || it.stable })
            assertEquals(
                listOf(
                    "alexandrite.base: You are helper",
                    "agent.instructions.1: The operator likes tea.",
                ),
                assembler.sections(turn(helper), ChatKind.DIRECT).withoutMedia().map { it.line() },
            )
            assertEquals(
                listOf(BASE_SECTION, MEDIA_SECTION),
                assembler.sections(turn(helper), null).map { it.id },
            )
            assertFailsWith<IllegalArgumentException> {
                assembler.sections(turn(testTurn { agent(AgentId("other")) }), ChatKind.DIRECT)
            }
        }
    }

    @Test
    fun `a turn is told of the parts that its offered tools use, in their order, and a turn offered none of none`() {
        val config = """
            {
              "agents": {
                "coder": {"description": "Codes.", "workspace": "/srv/coder"},
                "helper": {"description": "Helps.", "tools": {"deny": ["fs.*"]}}
              }
            }
        """.trimIndent()

        agentHarness(config) { tool(recordingTool("fs.read")) }.execute {
            val assembler = get<PromptAssembler>()
            val coder = testTurn { agent(AgentId("coder")).language(LanguageTag("zh-CN")) }
            val helper = testTurn { agent(AgentId("helper")) }
            val conversationDirectory = get<ConversationDirectories>().path(AgentId("coder"), ConversationId("test"))

            val offered = setOf("fs.read", "skill.load", "chat.send")
            val sections = assembler.sections(turn(coder, offered), ChatKind.DIRECT)
            assertEquals(
                listOf(
                    BASE_SECTION,
                    TOOLS_SECTION,
                    DELIVERY_SECTION,
                    WORKSPACE_SECTION,
                    CONVERSATION_DIRECTORY_SECTION,
                    PROJECT_SECTION,
                    SKILLS_SECTION,
                    MEDIA_SECTION,
                    LANGUAGE_SECTION,
                ),
                sections.map { it.id },
            )
            assertEquals(workspaceSection(Path.of("/srv/coder")), sections[3])
            assertEquals(conversationDirectorySection(conversationDirectory), sections[4])
            assertEquals(
                listOf(BASE_SECTION, MEDIA_SECTION, LANGUAGE_SECTION),
                assembler.sections(turn(coder), ChatKind.DIRECT).map { it.id },
            )
            assertEquals(
                listOf(BASE_SECTION, MEDIA_SECTION),
                assembler.sections(turn(helper), ChatKind.DIRECT).map { it.id },
            )
        }
    }
}
