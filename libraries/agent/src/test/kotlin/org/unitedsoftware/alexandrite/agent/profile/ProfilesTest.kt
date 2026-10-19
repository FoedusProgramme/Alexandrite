package org.unitedsoftware.alexandrite.agent.profile

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.hostPaths
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.prompt.Persona
import org.unitedsoftware.alexandrite.agent.provider
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.runtime.ConventionalAgentPlaces
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.agent.ProfileSource
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilesTest {
    @TempDir
    lateinit var directory: Path

    private val configFile: Path get() = directory.resolve("alexandrite.json")
    private val store = MemoryStore()
    private val operator = testUser("operator", isOperator = true)

    private fun profiles(config: String = "{}", contributed: List<AgentProfile> = emptyList()): Profiles {
        val settings = settings(config)
        val directory = agentDirectory(settings, providers = listOf(provider(ScriptedModel())))
        val persona = Persona(
            directory,
            settings,
            hostPaths(configFile),
            ConventionalAgentPlaces(hostPaths(configFile)),
        ).also {
            blocking { it.onStart() }
        }
        val endpoints = Endpoints(listOf(provider(ScriptedModel())), settings, TestClock())
        return Profiles(directory, persona, contributed, TestRecords(store.records).of(PLUGIN), endpoints)
    }

    private fun write(name: String, text: String): Path =
        configFile.resolveSibling(name).also { it.parent.createDirectories() }.also { it.writeText(text) }

    // Config and plugin profiles.

    @Test
    fun `a config entry without channels is a profile, its instructions inline and from its file`() {
        write("agents/coder/PERSONA.md", "Read the code first.")
        val config = """
            {
              "agents": {
                "coder": {
                  "description": "Codes.",
                  "instructions": ["Be brief."],
                  "model": "scripted/test-model",
                  "reasoning": "high",
                  "tools": {"allow": ["fs.*"], "deny": ["fs.write"]},
                  "permission": {"mode": "ask"},
                  "maxRounds": 10,
                  "maxTurnTokens": 1000
                }
              }
            }
        """.trimIndent()

        val summary = blocking { profiles(config).profile(AgentId("coder")) }

        assertEquals(ProfileSource.Config, summary?.source)
        assertEquals(
            AgentProfile(
                AgentId("coder"),
                "coder",
                "Codes.",
                "Be brief.\n\nRead the code first.",
                ModelRef.parse("scripted/test-model"),
                ReasoningEffort.HIGH,
                listOf("fs.*"),
                listOf("fs.write"),
                PermissionMode.ASK,
                10,
                1000,
            ),
            summary?.profile,
        )
    }

    @Test
    fun `a config entry's own name wins, else its id, and it needs no instruction file`() {
        val config = """{"agents": {"coder": {"description": "Codes.", "name": "Coder"}}}"""

        val all = blocking { profiles(config).all() }

        assertEquals(listOf("Coder"), all.map { it.profile.name })
        assertEquals("", all.single().profile.instructions)
    }

    @Test
    fun `a plugin-contributed profile is listed, an id a config entry or another plugin uses fails the start`() {
        val reviewer = AgentProfile(AgentId("reviewer"), "Reviewer", "Reviews code.", "Be strict.")

        val all = blocking { profiles(contributed = listOf(reviewer)).all() }

        assertEquals(listOf(AgentId("reviewer") to ProfileSource.Plugin), all.map { it.profile.id to it.source })
        assertFailsWith<ConfigException> {
            profiles("""{"agents": {"reviewer": {"description": "Codes."}}}""", listOf(reviewer))
        }
        assertFailsWith<ConfigException> { profiles(contributed = listOf(reviewer, reviewer)) }
    }

    @Test
    fun `a config entry named general replaces the built-in profile`() {
        val config = """{"agents": {"general": {"description": "A custom one."}}}"""

        val all = blocking { profiles(config, listOf(GeneralProfile())).all() }

        assertEquals(
            listOf(Triple(AgentId("general"), "A custom one.", ProfileSource.Config)),
            all.map { Triple(it.profile.id, it.profile.description, it.source) },
        )
    }

    // Precedence: config, then plugin, then the agent's own records.

    @Test
    fun `a record of an id that config or a plugin already uses is never listed or editable`() {
        val reviewer = AgentProfile(AgentId("reviewer"), "Plugin's", "Plugin's own.", "Be strict.")
        val catalog = profiles("""{"agents": {"coder": {"description": "Codes."}}}""", listOf(reviewer))

        assertFailsWith<ToolRefusal> { blocking { catalog.create(draft("coder"), operator) } }
        assertFailsWith<ToolRefusal> { blocking { catalog.create(draft("reviewer"), operator) } }
    }

    // profile.create.

    @Test
    fun `a created profile is listed with who made it and when`() {
        val catalog = profiles()

        val created = blocking { catalog.create(draft("helper", description = "Helps."), operator) }
        val summary = blocking { catalog.profile(AgentId("helper")) }
        val source = summary?.source as? ProfileSource.Created

        assertEquals("Helps.", created.description)
        assertEquals(operator, source?.by)
        assertEquals(created, summary?.profile)
    }

    @Test
    fun `an id already created cannot be created again`() {
        val catalog = profiles()
        blocking { catalog.create(draft("helper"), operator) }

        assertFailsWith<ToolRefusal> { blocking { catalog.create(draft("helper"), operator) } }
    }

    @Test
    fun `validation problems are refused before anything is created`() {
        val catalog = profiles()
        fun fails(draft: ProfileDraft) = assertFailsWith<ToolRefusal> { blocking { catalog.create(draft, operator) } }

        fails(draft("Not-An-Id"))
        fails(draft("helper", name = " "))
        fails(draft("helper", description = ""))
        fails(draft("helper", instructions = " "))
        fails(draft("helper", toolsAllow = listOf("Fs.*")))
        fails(draft("helper", model = "not a model"))
        fails(draft("helper", model = "unknown/m"))
        fails(draft("helper", reasoning = "extreme"))
        fails(draft("helper", mode = "bypass"))
        fails(draft("helper", mode = "yolo"))
        fails(draft("helper", maxRounds = 0))
        fails(draft("helper", maxTurnTokens = 0))
        assertEquals(emptyList(), blocking { catalog.all() })
    }

    // profile.edit.

    @Test
    fun `an edit keeps what it leaves out, sets what it gives and clears a nullable field set to null`() {
        val catalog = profiles()
        blocking {
            catalog.create(
                draft("helper", model = "scripted/test-model", mode = "ask", maxRounds = 5, maxTurnTokens = 100),
                operator,
            )
        }

        val edit = ProfileEdit(
            description = "Helps a lot.",
            model = SettingChange.Reset,
            mode = SettingChange.SetTo("manual"),
            maxTurnTokens = SettingChange.Reset,
        )
        val (before, after) = blocking { catalog.plannedEdit(AgentId("helper"), edit) }
        blocking { catalog.persistEdit(after, operator) }
        val stored = blocking { catalog.profile(AgentId("helper")) }?.profile

        assertEquals("Helps.", before.description)
        assertEquals("Helps a lot.", after.description)
        assertEquals(null, after.model)
        assertEquals(PermissionMode.MANUAL, after.mode)
        assertEquals(5, after.maxRounds)
        assertEquals(null, after.maxTurnTokens)
        assertEquals(after, stored)
    }

    @Test
    fun `editing an unknown, a config's or a plugin's profile is refused`() {
        val reviewer = AgentProfile(AgentId("reviewer"), "Reviewer", "Reviews.", "Be strict.")
        val catalog = profiles(contributed = listOf(reviewer))

        assertFailsWith<ToolRefusal> { blocking { catalog.plannedEdit(AgentId("ghost"), ProfileEdit()) } }
        assertFailsWith<ToolRefusal> { blocking { catalog.plannedEdit(AgentId("reviewer"), ProfileEdit()) } }
    }

    // profile.remove.

    @Test
    fun `removing a created profile takes it off the list, and removing it again is refused`() {
        val catalog = profiles()
        blocking { catalog.create(draft("helper"), operator) }

        val removed = blocking { catalog.plannedRemove(AgentId("helper")) }
        blocking { catalog.persistRemove(AgentId("helper")) }

        assertEquals("helper", removed.id.value)
        assertNull(blocking { catalog.profile(AgentId("helper")) })
        assertFailsWith<ToolRefusal> { blocking { catalog.plannedRemove(AgentId("helper")) } }
    }

    // Restart.

    @Test
    fun `a created profile survives a restart, read from the same store`() {
        blocking { profiles().create(draft("helper", description = "Helps."), operator) }

        val restarted = blocking { profiles().profile(AgentId("helper")) }

        assertTrue(restarted != null)
        assertEquals("Helps.", restarted.profile.description)
        assertEquals(operator, (restarted.source as? ProfileSource.Created)?.by)
    }

    private fun draft(
        id: String,
        name: String = "Helper",
        description: String = "Helps.",
        instructions: String = "Be helpful.",
        model: String? = null,
        reasoning: String? = null,
        toolsAllow: List<String> = listOf("*"),
        toolsDeny: List<String> = emptyList(),
        mode: String? = null,
        maxRounds: Int? = null,
        maxTurnTokens: Long? = null,
    ): ProfileDraft = ProfileDraft(
        id,
        name,
        description,
        instructions,
        model,
        reasoning,
        toolsAllow,
        toolsDeny,
        mode,
        maxRounds,
        maxTurnTokens,
    )

    private companion object {
        const val PLUGIN = "alexandrite-agent"
    }
}

/** Creates the planned candidate and stores it, as the agent's tool does across two calls. */
private suspend fun Profiles.create(draft: ProfileDraft, by: ChatUser?): AgentProfile {
    val profile = plannedCreate(draft)
    persistCreate(profile, by)
    return profile
}
