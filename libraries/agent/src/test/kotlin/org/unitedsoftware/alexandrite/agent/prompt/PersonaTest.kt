package org.unitedsoftware.alexandrite.agent.prompt

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.assertStartFails
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.hostPaths
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.runtime.ConventionalAgentPlaces
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonaTest {
    @TempDir
    lateinit var directory: Path

    private val configFile: Path get() = directory.resolve("alexandrite.json")

    private fun persona(config: String, configFile: Path? = this.configFile): Persona {
        val settings = settings(config)
        return Persona(
            agentDirectory(settings),
            settings,
            hostPaths(configFile),
            ConventionalAgentPlaces(hostPaths(configFile)),
        ).also {
            blocking { it.onStart() }
        }
    }

    private fun Persona.texts(
        agent: String = "coder",
        chatKind: ChatKind? = ChatKind.DIRECT,
        turnKind: TurnKind = TurnKind.MESSAGE,
    ): List<String> = blocking { sections(AgentId(agent), PersonaConditions(chatKind, turnKind)) }
        .map { "${it.id}: ${it.text}" }

    private fun write(name: String, text: String, modified: Instant = Instant.parse("2026-01-01T00:00:00Z")): Path =
        directory.resolve(name).also {
            it.parent.createDirectories()
            it.writeText(text)
            Files.setLastModifiedTime(it, FileTime.from(modified))
        }

    private fun coder(fields: String) = """{"agents": {"coder": {"description": "Codes.", $fields}}}"""

    @Test
    fun `inline instructions come first, then the files in config order, each a section numbered from 1`() {
        write("a.md", "File A.")
        val b = write("persona/b.md", "File B.")

        val persona = persona(coder(""""instructions": ["One.", "Two."], "instructionFiles": ["a.md", "$b"]"""))

        assertEquals(
            listOf(
                "agent.instructions.1: One.",
                "agent.instructions.2: Two.",
                "agent.instructions.3: File A.",
                "agent.instructions.4: File B.",
            ),
            persona.texts(),
        )
    }

    @Test
    fun `an agent that lists no files loads its PERSONA_md when there is one`() {
        write("agents/coder/PERSONA.md", "I am the coder.")
        write("other.md", "Other.")
        val config = """
            {
              "agents": {
                "coder": {"description": "Codes.", "instructions": ["Inline."]},
                "helper": {"description": "Helps.", "instructions": ["Help."]},
                "reviewer": {"description": "Reviews.", "instructionFiles": ["other.md"]}
              }
            }
        """.trimIndent()
        write("agents/reviewer/PERSONA.md", "Not read.")

        val persona = persona(config)

        assertEquals(listOf("agent.instructions.1: Inline.", "agent.instructions.2: I am the coder."), persona.texts())
        assertEquals(listOf("agent.instructions.1: Help."), persona.texts("helper"))
        assertEquals(listOf("agent.instructions.1: Other."), persona.texts("reviewer"))
        assertEquals(
            listOf("agent.instructions.1: Inline."),
            persona(coder(""""instructions": ["Inline."]"""), configFile = null).texts(),
        )
    }

    @Test
    fun `a file that changed is read again at the next turn and one whose time and size did not is not`() {
        val file = write("a.md", "First")
        val persona = persona(coder(""""instructionFiles": ["a.md"]"""))
        write("a.md", "Second", Instant.parse("2026-01-02T00:00:00Z"))

        val lines = logged { assertEquals(listOf("agent.instructions.1: Second"), persona.texts()) }
        write("a.md", "Thirds", Instant.parse("2026-01-02T00:00:00Z"))

        assertEquals(listOf("agent.instructions.1: Second"), persona.texts())
        assertEquals(listOf("INFO Agent 'coder' reads its instruction file $file again"), lines)
    }

    @Test
    fun `a listed file that cannot be read later keeps its last text with one warning`() {
        val file = write("a.md", "First")
        val persona = persona(coder(""""instructionFiles": ["a.md"]"""))
        file.deleteExisting()

        val lines = logged { repeat(2) { assertEquals(listOf("agent.instructions.1: First"), persona.texts()) } }

        assertEquals(
            listOf(
                "WARN Agent 'coder' keeps the last text of its instruction file $file, which cannot be read: it does " +
                    "not exist",
            ),
            lines,
        )
        write("a.md", "Back", Instant.parse("2026-01-03T00:00:00Z"))
        assertEquals(listOf("agent.instructions.1: Back"), persona.texts())
    }

    @Test
    fun `the conventional file may come and go`() {
        val persona = persona(coder(""""instructions": ["Inline."]"""))
        assertEquals(listOf("agent.instructions.1: Inline."), persona.texts())

        val file = write("agents/coder/PERSONA.md", "Now there.")
        assertEquals(listOf("agent.instructions.1: Inline.", "agent.instructions.2: Now there."), persona.texts())

        file.deleteExisting()
        assertEquals(listOf("agent.instructions.1: Inline."), persona.texts())
    }

    @Test
    fun `a file with conditions is loaded for the chat kinds and turn kinds it names`() {
        write("any.md", "Any.")
        write("direct.md", "Direct.")
        write("heartbeat.md", "Heartbeat.")
        write("both.md", "Both.")
        val files = """"any.md", {"file": "direct.md", "when": ["direct", "unknown"]},""" +
            """{"file": "heartbeat.md", "when": ["heartbeat"]}, {"file": "both.md", "when": ["group", "heartbeat"]}"""
        val persona = persona(coder(""""instructionFiles": [$files]"""))

        fun numbers(chatKind: ChatKind?, turnKind: TurnKind) = persona.texts(chatKind = chatKind, turnKind = turnKind)
            .map { it.substringBefore(':').substringAfterLast('.').toInt() }

        assertEquals(listOf(1, 2), numbers(ChatKind.DIRECT, TurnKind.MESSAGE))
        assertEquals(listOf(1, 2, 3), numbers(ChatKind.DIRECT, TurnKind.HEARTBEAT))
        assertEquals(listOf(1), numbers(ChatKind.GROUP, TurnKind.MESSAGE))
        assertEquals(listOf(1, 3, 4), numbers(ChatKind.GROUP, TurnKind.HEARTBEAT))
        assertEquals(listOf(1, 3), numbers(null, TurnKind.HEARTBEAT))
        assertEquals(listOf(1, 2), numbers(ChatKind.UNKNOWN, TurnKind.MESSAGE))
    }

    @Test
    fun `a file longer than the limits is cut with a marker and a warning`() {
        val a = write("a.md", "0123456789ABC")
        write("b.md", "abcdefgh")
        val c = write("c.md", "xyz")
        val config = """
            {
              "persona": {"maxFileChars": 10, "maxTotalChars": 15},
              "agents": {"coder": {"description": "Codes.", "instructionFiles": ["a.md", "b.md", "c.md"]}}
            }
        """.trimIndent()

        val persona = persona(config)
        lateinit var texts: List<String>
        val lines = logged { repeat(2) { texts = persona.texts() } }

        assertEquals(
            listOf(
                "agent.instructions.1: 0123456789" + cutMarker(10, 13),
                "agent.instructions.2: abcde" + cutMarker(5, 8),
                "agent.instructions.3: " + cutMarker(0, 3),
            ),
            texts,
        )
        assertEquals(
            "WARN Instruction file $a of agent 'coder' has 13 characters: the prompt takes the first 10 " +
                "(agent.persona.maxFileChars 10, maxTotalChars 15)",
            lines.first(),
        )
        assertEquals(3, lines.size, "$lines")
        assertEquals("\n\n[Cut: the first 5 of 8 characters of this file.]", cutMarker(5, 8))
        assertEquals(true, c.toString() in lines.last())
    }

    @Test
    fun `the total limit counts only the files a turn loads`() {
        write("private.md", "p".repeat(12))
        write("shared.md", "shared")
        val config = """
            {
              "persona": {"maxFileChars": 20, "maxTotalChars": 15},
              "agents": {
                "coder": {
                  "description": "Codes.",
                  "instructionFiles": [{"file": "private.md", "when": ["direct"]}, "shared.md"]
                }
              }
            }
        """.trimIndent()

        val persona = persona(config)

        assertEquals(listOf("agent.instructions.2: shared"), persona.texts(chatKind = ChatKind.GROUP))
        assertEquals(
            listOf("agent.instructions.1: " + "p".repeat(12), "agent.instructions.2: sha" + cutMarker(3, 6)),
            persona.texts(chatKind = ChatKind.DIRECT),
        )
    }

    @Test
    fun `a cut never splits a character outside the basic plane`() {
        write("a.md", "abc😀def")
        val config = """
            {
              "persona": {"maxFileChars": 4},
              "agents": {"coder": {"description": "Codes.", "instructionFiles": ["a.md"]}}
            }
        """.trimIndent()

        val persona = persona(config)

        assertEquals(listOf("agent.instructions.1: abc" + cutMarker(3, 8)), persona.texts())
    }

    @Test
    fun `a blank file adds no section`() {
        write("a.md", " \n")

        assertEquals(emptyList(), persona(coder(""""instructionFiles": ["a.md"]""")).texts())
    }

    @Test
    fun `a listed file that is missing at the start fails it`() {
        val missing = directory.resolve("missing.md")

        agentHarness(coder(""""instructionFiles": ["missing.md"]""")) { configFile(configFile) }.assertStartFails(
            StartStage.START,
            "Invalid config at 'agent.agents.coder.instructionFiles': the instruction file $missing does not exist",
        )
    }

    @Test
    fun `a file that is no UTF-8 text fails the start`() {
        val file = directory.resolve("a.md").apply { writeBytes(byteArrayOf(0x48, 0xC3.toByte(), 0x28)) }

        agentHarness(coder(""""instructionFiles": ["a.md"]""")) { configFile(configFile) }.assertStartFails(
            StartStage.START,
            "Invalid config at 'agent.agents.coder.instructionFiles': the instruction file $file is no UTF-8 text",
        )
    }

    @Test
    fun `a relative file fails the start when the host read no config file`() {
        agentHarness(coder(""""instructionFiles": ["a.md"]""")).assertStartFails(
            StartStage.START,
            "Invalid config at 'agent.agents.coder.instructionFiles': the instruction file 'a.md' is relative, but " +
                "the host read no config file to resolve it against: give an absolute path",
        )
    }
}
