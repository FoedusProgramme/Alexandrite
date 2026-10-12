package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.agentDirectory
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.hostPaths
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.prompt.Persona
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.permission.Clearance
import org.foedusprogramme.alexandrite.sdk.permission.ClearanceKind
import org.foedusprogramme.alexandrite.sdk.tool.CallReview
import org.foedusprogramme.alexandrite.sdk.tool.ReviewPreview
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.testkit.testDelegatedTurn
import org.foedusprogramme.alexandrite.testkit.testToolContext
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUser
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PersonaEditToolTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()
    private val configFile: Path get() = root.resolve("config/alexandrite.json")
    private val personas: Path get() = configFile.resolveSibling("agents")
    private val coder: TurnInfo = testTurn {
        agent(AgentId("coder"))
        actor(testUser("admin", isAdmin = true))
    }

    private fun tool(
        agents: String = """{"coder": {}, "helper": {}}""",
        configFile: Path? = this.configFile,
    ): PersonaEditTool {
        val settings = settings("""{"agents": $agents}""")
        val paths = hostPaths(configFile, root.resolve("data"))
        return PersonaEditTool(
            AgentFloor(paths, Workspaces(paths, settings), settings),
            Persona(agentDirectory(settings), settings, paths),
            paths,
        )
    }

    private fun write(name: String, text: String): Path =
        personas.resolve(name).also { it.parent.createDirectories() }.also { it.writeText(text) }

    private fun arguments(file: Any, operation: String, new: String, old: String? = null): JsonObject = JsonObject(
        listOfNotNull(
            "file" to JsonPrimitive("$file"),
            "operation" to JsonPrimitive(operation),
            "new" to JsonPrimitive(new),
            old?.let { "old" to JsonPrimitive(it) },
        ).toMap(),
    )

    /** `refused: …` or `problem: …` of the review, else the result of the call run with the review's snapshot. */
    private fun PersonaEditTool.run(arguments: JsonObject, turn: TurnInfo = coder): String {
        val review = blocking { review(arguments, testToolContext(turn)) }
        review.refusal?.let { return "refused: $it" }
        review.problem?.let { return "problem: $it" }
        val cleared = testToolContext(turn, clearance = Clearance(ClearanceKind.NONE, null, review.snapshot))
        val result = blocking { execute(arguments, cleared) }
        val text = result.content.joinToString("") { (it as TextPart).text }
        return if (result.isError) "failed: $text" else text
    }

    private fun hash(file: Path): String = "sha256:" +
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

    @Test
    fun `the call is titled by its file, of activity edit`() {
        val definition = tool().definition
        assertEquals(ToolActivity.EDIT to listOf("file"), definition.activity to definition.titleArguments)
    }

    @Test
    fun `replace changes the one occurrence, and append adds a line break first when the file ends without one`() {
        val file = write("coder/PERSONA.md", "I like tea.")
        val tool = tool()

        val replaced = tool.run(arguments("PERSONA.md", "replace", "coffee", old = "tea"))
        val appended = tool.run(arguments(file, "append", "Be brief.\n"))

        assertEquals(
            listOf("+1 -1", "+2 -1").map { "Edited $file: $it lines. Your next turn reads it." },
            listOf(replaced, appended),
        )
        assertEquals("I like coffee.\nBe brief.\n", file.readText())
    }

    @Test
    fun `append creates a file, which the prompt loads only when it is one of the agent's instruction files`() {
        personas.resolve("coder").createDirectories()
        val persona = personas.resolve("coder/PERSONA.md")
        val notes = personas.resolve("coder/NOTES.md")
        val listed = """{"coder": {"instructionFiles": ["agents/coder/NOTES.md"]}}"""

        val conventional = tool().run(arguments("PERSONA.md", "append", "Be kind.\n"))
        val unlisted = tool().run(arguments("NOTES.md", "append", "Tea.\n"))
        val configured = tool(listed).run(arguments("NOTES.md", "append", "Cake.\n"))

        assertEquals(
            listOf(
                "Created $persona: +1 -0 lines. Your next turn reads it.",
                "Created $notes: +1 -0 lines. It is no instruction file of yours, so your prompt does not load it: " +
                    "the operator lists instruction files at agent.agents.coder.instructionFiles.",
                "Edited $notes: +1 -0 lines. Your next turn reads it.",
            ),
            listOf(conventional, unlisted, configured),
        )
        assertEquals("Tea.\nCake.\n", notes.readText())
    }

    @Test
    fun `replace refuses text that is missing or found twice, a missing file and an edit that changes nothing`() {
        val file = write("coder/PERSONA.md", "tea and tea\n")
        val tool = tool()

        assertEquals(
            listOf(
                "problem: The old text is not in $file: read the file again with fs.read if unsure of it.",
                "problem: The old text occurs more than once in $file: give more of the text around it.",
                "problem: Nothing exists at ${personas.resolve("coder/OTHER.md")}: append creates a file.",
                "problem: The edit changes nothing in $file.",
                "problem: Give 'old', the exact text to replace.",
                "problem: Give 'operation' as \"replace\" or \"append\".",
            ),
            listOf(
                tool.run(arguments("PERSONA.md", "replace", "x", old = "coffee")),
                tool.run(arguments("PERSONA.md", "replace", "x", old = "tea")),
                tool.run(arguments("OTHER.md", "replace", "x", old = "tea")),
                tool.run(arguments("PERSONA.md", "replace", "tea and tea", old = "tea and tea")),
                tool.run(arguments("PERSONA.md", "replace", "x")),
                tool.run(arguments("PERSONA.md", "insert", "x")),
            ),
        )
        assertEquals("tea and tea\n", file.readText())
    }

    @Test
    fun `only the Markdown files directly in the agent's own persona directory are edited`() {
        val helper = write("helper/PERSONA.md", "Helper.\n")
        write("coder/PERSONA.md", "Coder.\n")
        val shared = configFile.resolveSibling("shared.md").also { it.writeText("Shared.\n") }
        val directory = personas.resolve("coder")
        val only = "persona.edit edits only the Markdown files directly in your persona directory $directory."

        val results = listOf("../helper/PERSONA.md", "notes/a.md", "settings.json", "$shared", "/tmp/x.md").map {
            tool().run(arguments(it, "append", "More.\n"))
        }

        assertEquals(
            listOf(
                "refused: $helper is refused (Alexandrite's configuration): $only",
                "refused: ${directory.resolve("notes/a.md")} is refused (a place outside the persona directory of " +
                    "agent 'coder'): $only",
                "refused: ${directory.resolve("settings.json")} is refused (a persona file that is no Markdown " +
                    "file): $only",
                "refused: $shared is refused (Alexandrite's configuration): $only",
                "refused: /tmp/x.md is refused (a place outside the persona directory of agent 'coder'): $only",
            ),
            results,
        )
        assertEquals("Helper.\n" to "Shared.\n", helper.readText() to shared.readText())
        assertFalse(directory.resolve("settings.json").exists())
    }

    @Test
    fun `a delegated run never edits a persona file`() {
        val file = write("coder/PERSONA.md", "Coder.\n")

        val result = tool().run(arguments("PERSONA.md", "append", "More.\n"), testDelegatedTurn(coder))

        assertEquals("refused: A delegated run never edits persona files." to "Coder.\n", result to file.readText())
    }

    @Test
    fun `without a config file or a persona directory nothing is edited`() {
        assertEquals(
            listOf(
                "problem: You have no persona directory, as the host read no config file.",
                "problem: Your persona directory ${personas.resolve("coder")} does not exist: the operator creates it.",
            ),
            listOf(
                tool(configFile = null).run(arguments("PERSONA.md", "append", "More.\n")),
                tool().run(arguments("PERSONA.md", "append", "More.\n")),
            ),
        )
    }

    @Test
    fun `the review shows the diff and snapshots the file, which must not change before the call runs`() {
        val file = write("coder/PERSONA.md", "a\nb\nc\n")
        val tool = tool()
        val arguments = arguments("PERSONA.md", "replace", "B", old = "b")

        val hash = hash(file)

        val review = blocking { tool.review(arguments, testToolContext(coder)) }
        file.writeText("a\nb\nc\nd\n")
        val cleared = testToolContext(coder, clearance = Clearance(ClearanceKind.HUMAN, null, review.snapshot))
        val result = blocking { tool.execute(arguments, cleared) }

        assertEquals(
            CallReview(
                preview = ReviewPreview.Diff(file, "--- $file\n+++ $file\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n"),
                snapshot = hash,
            ),
            review,
        )
        assertEquals(
            true to "$file changed since this call was reviewed: repeat the call if it still applies.",
            result.isError to (result.content.single() as TextPart).text,
        )
        assertEquals("a\nb\nc\nd\n", file.readText())
    }

    @Test
    fun `each write is logged with the file and its new hash`() {
        val file = write("coder/PERSONA.md", "Coder.\n")
        val tool = tool()

        val lines = logged { tool.run(arguments("PERSONA.md", "append", "Kind.\n")) }

        assertEquals(listOf("INFO Agent 'coder' wrote its persona file $file (${hash(file)})"), lines)
    }
}
