package org.unitedsoftware.alexandrite.agent.profile

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.hostPaths
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.prompt.Persona
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.runtime.ConventionalAgentPlaces
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfileToolsTest {
    private val operator = testUser("operator", isOperator = true)
    private val turn = testTurn { actor(operator) }
    private val catalog = Profiles(
        agentDirectory(settings("{}")),
        Persona(
            agentDirectory(settings("{}")),
            settings("{}"),
            hostPaths(null),
            ConventionalAgentPlaces(hostPaths(null)),
        ),
        emptyList(),
        TestRecords().of("alexandrite-agent"),
        Endpoints(emptyList(), settings("{}"), TestClock()),
    )

    private suspend fun create(id: String, description: String = "Helps.", name: String = "Helper") {
        val draft = ProfileDraft(id, name, description, "Be helpful.")
        catalog.persistCreate(catalog.plannedCreate(draft), operator)
    }

    private fun arguments(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(
        fields.associate { (key, value) -> key to (value?.let { JsonPrimitive("$it") } ?: JsonNull) },
    )

    @Test
    fun `a profile goes from create, through list and an edit's diff preview, to remove`() {
        val createArgs = arguments(
            "id" to "reviewer",
            "name" to "Reviewer",
            "description" to "Reviews.",
            "instructions" to "Be strict.",
        )
        blocking { ProfileCreateTool(catalog).execute(createArgs, testToolContext(turn)) }

        val listed = blocking { ProfileListTool(catalog).execute(arguments(), testToolContext(turn)) }
        assertTrue("reviewer (Reviewer): Reviews." in (listed.content.single() as TextPart).text)

        val editTool = ProfileEditTool(catalog)
        val editArgs = arguments("id" to "reviewer", "description" to "Reviews harder.")
        val reviewed = blocking { editTool.review(editArgs, testToolContext(turn)) }
        val diff = assertIs<ReviewPreview.Diff>(reviewed.preview)
        assertTrue("-description: Reviews." in diff.unified && "+description: Reviews harder." in diff.unified)
        val editCleared = testToolContext(turn, clearance = Clearance(ClearanceKind.NONE, null, reviewed.snapshot))
        blocking { editTool.execute(editArgs, editCleared) }

        val removeTool = ProfileRemoveTool(catalog)
        val removeArgs = arguments("id" to "reviewer")
        val removePreview = blocking { removeTool.review(removeArgs, testToolContext(turn)) }
        val snapshot = removePreview.snapshot
        val removeCleared = testToolContext(turn, clearance = Clearance(ClearanceKind.NONE, null, snapshot))
        blocking { removeTool.execute(removeArgs, removeCleared) }

        assertEquals(emptyList(), blocking { catalog.all() })
        assertTrue(blocking { removeTool.execute(removeArgs, removeCleared) }.isError)
    }

    // profile.list.

    @Test
    fun `the writing tools say when a person reviews their change, which the gate decides by turn and taint`() {
        val tools = listOf(ProfileCreateTool(catalog), ProfileEditTool(catalog), ProfileRemoveTool(catalog))

        for (tool in tools) {
            val description = tool.definition.description
            assertTrue(
                description.endsWith(
                    "A person reviews the change first only when the turn is not an operator's own or the " +
                        "conversation took in untrusted content; otherwise it applies at once.",
                ),
                description,
            )
        }
    }

    @Test
    fun `it lists a created profile's id, name, description, model, tools, mode and source`() {
        blocking { create("reviewer", description = "Reviews code.", name = "Reviewer") }

        val result = blocking { ProfileListTool(catalog).execute(arguments(), testToolContext(turn)) }

        val text = (result.content.single() as TextPart).text
        val fields = listOf("reviewer (Reviewer): Reviews code.", "model=", "toolsAllow=*", "toolsDeny=", "mode=")
        (fields + "created by Operator").forEach { assertTrue(it in text, text) }
    }

    // profile.create.

    @Test
    fun `execute creates the profile that review already describes, a second call is refused`() {
        val tool = ProfileCreateTool(catalog)
        val args = arguments(
            "id" to "reviewer",
            "name" to "Reviewer",
            "description" to "Reviews.",
            "instructions" to "Be strict.",
        )

        val created = blocking { tool.execute(args, testToolContext(turn)) }
        val again = blocking { tool.execute(args, testToolContext(turn)) }

        assertEquals("Created profile reviewer (Reviewer).", (created.content.single() as TextPart).text)
        assertTrue(again.isError)
        assertEquals("reviewer", blocking { catalog.profile(AgentId("reviewer")) }?.profile?.id?.value)
    }

    @Test
    fun `a missing field is a problem, never a refusal, and nothing is created`() {
        val tool = ProfileCreateTool(catalog)

        val review = blocking { tool.review(arguments("id" to "reviewer"), testToolContext(turn)) }

        assertEquals("Give 'name' as a non-empty string.", review.problem)
        assertEquals(emptyList(), blocking { catalog.all() })
    }

    // profile.edit.

    @Test
    fun `an edit reviewed, then overtaken by another edit, is refused when it finally runs`() {
        blocking { create("reviewer") }
        val tool = ProfileEditTool(catalog)
        val args = arguments("id" to "reviewer", "description" to "Reviews harder.")
        val review = blocking { tool.review(args, testToolContext(turn)) }

        blocking { applyEdit("reviewer", ProfileEdit(description = "Changed first.")) }
        val cleared = testToolContext(turn, clearance = Clearance(ClearanceKind.NONE, null, review.snapshot))
        val result = blocking { tool.execute(args, cleared) }

        assertTrue(result.isError)
        assertEquals(
            "Profile 'reviewer' changed since this call was reviewed: repeat the call if it still applies.",
            (result.content.single() as TextPart).text,
        )
    }

    @Test
    fun `an edit that changes nothing is refused`() {
        blocking { create("reviewer") }
        val tool = ProfileEditTool(catalog)

        val review = blocking { tool.review(arguments("id" to "reviewer"), testToolContext(turn)) }

        assertEquals("The edit changes nothing in profile 'reviewer'.", review.problem)
    }

    // profile.remove.

    private suspend fun applyEdit(id: String, edit: ProfileEdit) {
        catalog.persistEdit(catalog.plannedEdit(AgentId(id), edit).second, operator)
    }
}
