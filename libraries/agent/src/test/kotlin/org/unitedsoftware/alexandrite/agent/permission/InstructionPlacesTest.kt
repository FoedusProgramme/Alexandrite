package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstructionPlacesTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path by lazy { directory.toRealPath() }
    private val config: Path by lazy { root.resolve("config").createDirectories() }
    private val project: Path by lazy { root.resolve("project").createDirectories() }
    private val elsewhere: Path by lazy { root.resolve("elsewhere").createDirectories() }
    private val coder = AgentId("coder")

    private fun harness(sandbox: String = "{}"): PluginHarness = agentHarness(
        """
        {
          "trustedFolders": [${JsonPrimitive("$project")}],
          "agents": {
            "coder": {"description": "Codes.", "sandbox": $sandbox},
            "writer": {"description": "Writes."}
          }
        }
        """.trimIndent(),
    ) {
        configFile(config.resolve("alexandrite.json"))
        dataRoot(root.resolve("data"))
    }

    @Test
    fun `the config, persona, skill, trusted instruction and protected places steer an agent, and others do not`() {
        harness().execute {
            val places = get<InstructionPlaces>()
            val kinds = listOf(
                config.resolve("alexandrite.json"),
                config.resolve("secrets.env"),
                config.resolve("agents/writer/PERSONA.md"),
                config.resolve("agents/writer/notes.md"),
                config.resolve("skills/deploy/SKILL.md"),
                config.resolve("agents/coder/skills/build/SKILL.md"),
                project.resolve("AGENTS.md"),
                project.resolve("src/claude.md"),
                project.resolve("src/.agents/skills/test/SKILL.md"),
                elsewhere.resolve("AGENTS.md"),
                elsewhere.resolve(".git/hooks/pre-commit"),
                root.resolve("data/workspaces/coder/notes.md"),
                project.resolve("src/Main.kt"),
            ).map { places.kind(it, coder) }

            assertEquals(
                listOf(
                    "Alexandrite's config file",
                    "Alexandrite's secrets file",
                    "an instruction file of agent 'writer'",
                    "the persona directory of agent 'writer'",
                    "a skill directory of agent 'coder'",
                    "the persona directory of agent 'coder'",
                    "a trusted folder's instruction file",
                    "a trusted folder's instruction file",
                    "a trusted folder's skills",
                    null,
                    "a place that the sandbox protects (.git/hooks)",
                    null,
                    null,
                ),
                kinds,
            )
        }
    }

    @Test
    fun `the agent's own protect list decides, and alexandrite spares its workspace and conversations`() {
        harness("""{"protect": [".npmrc", "alexandrite"]}""").execute {
            val places = get<InstructionPlaces>()
            val data = root.resolve("data")

            assertEquals(
                listOf(
                    "a place that the sandbox protects (.npmrc)",
                    null,
                    "Alexandrite's own places",
                    null,
                    null,
                ),
                listOf(
                    elsewhere.resolve(".npmrc"),
                    elsewhere.resolve(".git/hooks/pre-commit"),
                    data.resolve("alexandrite.db"),
                    data.resolve("workspaces/coder/notes.md"),
                    data.resolve("conversations/coder/c1/notes.md"),
                ).map { places.kind(it, coder) },
            )
        }
    }

    @Test
    fun `only a change of a file in such a place counts, and the sandbox keeps its commands out of them`() {
        harness().execute {
            val places = get<InstructionPlaces>()
            fun review(path: Path) = CallReview.builder().preview(ReviewPreview.Diff(path, "+x\n")).build()

            assertEquals(
                "a trusted folder's instruction file",
                places.written(coder, review(project.resolve("AGENTS.md"))),
            )
            assertEquals(null, places.written(coder, review(Path.of("profiles/main"))))
            assertEquals(null, places.written(coder, CallReview.builder().build()))
            val denied = places.sandboxPaths()
            for (path in listOf("alexandrite.json", "secrets.env", "agents/coder", "agents/coder/skills", "skills")) {
                assertTrue(config.resolve(path) in denied, "$path in $denied")
            }
        }
    }
}
