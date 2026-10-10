package org.foedusprogramme.alexandrite.agent.prompt

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.model.PromptSection
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.junit.jupiter.api.io.TempDir
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
            "coder": {"name": "Coder", "instructions": ["Be brief."]},
            "helper": {"instructionFiles": [{"file": "private.md", "when": ["direct"]}]}
          }
        }
    """.trimIndent()

    private fun write(name: String, text: String, modified: String) {
        val file = directory.resolve(name)
        file.parent.createDirectories()
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(modified)))
    }

    private fun PromptSection.line(): String = "$id: ${if (id == BASE_SECTION) text.lineSequence().first() else text}"

    @Test
    fun `a turn gets the base section, the agent's instructions and the chat's language, in that order`() {
        write("agents/coder/PERSONA.md", "Read the code first.", "2026-01-01T00:00:00Z")
        write("private.md", "The operator likes tea.", "2026-01-01T00:00:00Z")

        agentHarness(config) { configFile(directory.resolve("alexandrite.json")) }.execute {
            val assembler = get<PromptAssembler>()
            val coder = testTurn { agent(AgentId("coder")).language(LanguageTag("zh-CN")) }
            val helper = testTurn(TurnKind.HEARTBEAT) { agent(AgentId("helper")) }

            val sections = assembler.sections(coder, ChatKind.GROUP)
            assertEquals(
                listOf(
                    "alexandrite.base: You are Coder. You talk with people in chats through Alexandrite, which " +
                        "passes their messages to you and your replies back to them.",
                    "agent.instructions.1: Be brief.",
                    "agent.instructions.2: Read the code first.",
                    "chat.language: Answer in zh-CN unless the user writes in another language.",
                ),
                sections.map { it.line() },
            )
            assertEquals(true, sections.all { it.stable })
            assertEquals(
                listOf(
                    "alexandrite.base: You are helper. You talk with people in chats through Alexandrite, which " +
                        "passes their messages to you and your replies back to them.",
                    "agent.instructions.1: The operator likes tea.",
                ),
                assembler.sections(helper, ChatKind.DIRECT).map { it.line() },
            )
            assertEquals(listOf(BASE_SECTION), assembler.sections(helper, null).map { it.id })
            assertFailsWith<IllegalArgumentException> {
                assembler.sections(testTurn { agent(AgentId("other")) }, ChatKind.DIRECT)
            }
        }
    }

    @Test
    fun `a turn gets an instruction file as it is when the turn starts`() {
        write("agents/coder/PERSONA.md", "Read the code first.", "2026-01-01T00:00:00Z")
        write("private.md", "The operator likes tea.", "2026-01-01T00:00:00Z")

        agentHarness(config) { configFile(directory.resolve("alexandrite.json")) }.execute {
            val assembler = get<PromptAssembler>()
            val turn = testTurn { agent(AgentId("coder")) }
            suspend fun instructions() = assembler.sections(turn, ChatKind.DIRECT).drop(1).map { it.text }

            assertEquals(listOf("Be brief.", "Read the code first."), instructions())
            write("agents/coder/PERSONA.md", "Run the tests first.", "2026-01-02T00:00:00Z")
            assertEquals(listOf("Be brief.", "Run the tests first."), instructions())
        }
    }
}
