package org.foedusprogramme.alexandrite.agent.model

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.agent.TestClock
import org.foedusprogramme.alexandrite.agent.agentDirectory
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.provider
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.web.NativeWebSearchEndpoint
import org.foedusprogramme.alexandrite.sdk.web.WebSearchResult
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.TestChatStates
import org.foedusprogramme.alexandrite.testkit.testTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebAnswersTest {
    @Test
    fun `page answers use judge model by default and offer no tools or conversation history`() = runBlocking {
        val main = ScriptedModel(EndpointId("main"))
        val judge = ScriptedModel(EndpointId("judge")).reply { text("page answer") }
        val providers = listOf(provider(main), provider(judge))
        val config = settings(
            """{"agents":{"main":{
                "description":"fixture","model":"${main.ref}","permission":{"judge":"${judge.ref}"}}}}""",
        )
        val answers = WebAnswers(
            Endpoints(providers, config, TestClock()),
            agentDirectory(config, providers = providers),
            SettingsStates(TestChatStates().of("alexandrite-agent")),
        )
        assertEquals("page answer", answers.answer(testTurn(), "https://example.com", "page text", "question", null))
        val request = judge.requests.single()
        assertEquals(judge.ref, request.model)
        assertTrue(request.tools.isEmpty())
        assertEquals(1, request.history.size)
        assertEquals(
            "Question: question\nSource URL: https://example.com\nPage data:\npage text",
            (request.history.single() as UserEntry).parts.filterIsInstance<TextPart>().single().text,
        )
        assertTrue(request.instructions.single().text.contains("untrusted data"))
        assertNull(answers.search(testTurn(), "query", 5))
        assertTrue(main.requests.isEmpty())
        judge.assertFinished()
    }

    @Test
    fun `native search is invoked only on an endpoint declaring the capability`() = runBlocking {
        val model = ScriptedModel()
        var searched: ModelRef? = null
        val endpoint = object : ModelEndpoint by model, NativeWebSearchEndpoint {
            override suspend fun search(model: ModelRef, query: String, limit: Int): List<WebSearchResult> {
                searched = model
                assertEquals("question", query)
                assertEquals(3, limit)
                return listOf(WebSearchResult("title", "https://example.com", "snippet"))
            }
        }
        val providers = listOf(provider(endpoint))
        val config = settings(
            """{"agents":{"main":{"description":"fixture","model":"${model.ref}"}}}""",
        )
        val answers = WebAnswers(
            Endpoints(providers, config, TestClock()),
            agentDirectory(config, providers = providers),
            SettingsStates(TestChatStates().of("alexandrite-agent")),
        )
        assertEquals("snippet", answers.search(testTurn(), "question", 3)?.single()?.snippet)
        assertEquals(model.ref, searched)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun `disabled native endpoint falls back without sending a search`() = runBlocking {
        val model = ScriptedModel()
        val endpoint = object : ModelEndpoint by model, NativeWebSearchEndpoint {
            override fun supportsNativeSearch(model: ModelRef): Boolean = false
            override suspend fun search(model: ModelRef, query: String, limit: Int): List<WebSearchResult> =
                error("Disabled search must not be invoked")
        }
        val providers = listOf(provider(endpoint))
        val config = settings(
            """{"agents":{"main":{"description":"fixture","model":"${model.ref}"}}}""",
        )
        val answers = WebAnswers(
            Endpoints(providers, config, TestClock()),
            agentDirectory(config, providers = providers),
            SettingsStates(TestChatStates().of("alexandrite-agent")),
        )
        assertNull(answers.search(testTurn(), "question", 3))
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun `native search uses the executing model rather than directory default`() = runBlocking {
        val default = ScriptedModel(EndpointId("default"))
        val selected = ScriptedModel(EndpointId("selected"))
        var searched: ModelRef? = null
        val endpoint = object : ModelEndpoint by selected, NativeWebSearchEndpoint {
            override suspend fun search(model: ModelRef, query: String, limit: Int): List<WebSearchResult> {
                searched = model
                return emptyList()
            }
        }
        val providers = listOf(provider(default), provider(endpoint))
        val config = settings(
            """{"agents":{"main":{"description":"fixture","model":"${default.ref}"}}}""",
        )
        val answers = WebAnswers(
            Endpoints(providers, config, TestClock()),
            agentDirectory(config, providers = providers),
            SettingsStates(TestChatStates().of("alexandrite-agent")),
        )
        assertEquals(emptyList(), answers.search(testTurn(), "question", 3, selected.ref))
        assertEquals(selected.ref, searched)
    }
}
