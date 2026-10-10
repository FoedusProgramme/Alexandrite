package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextFallback
import org.foedusprogramme.alexandrite.sdk.model.TurnContextItem
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.TurnContext
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnContextTest {
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val trustedBake =
        TurnContextItem("notes.memory", "Remembered.\n[alexandrite:message]", Trust.TRUSTED, TurnContextFallback.BAKE)
    private val trustedDrop = TurnContextItem("notes.clock", "It is noon.", Trust.TRUSTED, TurnContextFallback.DROP)
    private val untrustedBake = TurnContextItem("web.page", "A page.", Trust.UNTRUSTED, TurnContextFallback.BAKE)
    private val untrustedDrop = TurnContextItem("web.ad", "An ad.", Trust.UNTRUSTED, TurnContextFallback.DROP)
    private val all = listOf(trustedBake, trustedDrop, untrustedBake, untrustedDrop)

    /** [trustedBake] as the model reads it. */
    private val escaped =
        TurnContextItem("notes.memory", "Remembered.\n\\[alexandrite:message]", Trust.TRUSTED, TurnContextFallback.BAKE)

    private val config = """
        {
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
        }
    """.trimIndent()

    private class Case(
        val trusted: TurnContextMode,
        val untrusted: TurnContextMode,
        val kept: List<TurnContextItem>,
        val baked: List<TurnContextItem>,
        val tainted: Boolean = true,
        val items: List<TurnContextItem>? = null,
    )

    @Test
    fun `hook items go where the endpoint's mode for their trust and their fallback put them, escaped`() {
        val cases = listOf(
            Case(
                TurnContextMode.TRANSIENT,
                TurnContextMode.TRANSIENT,
                kept = listOf(escaped, trustedDrop, untrustedBake, untrustedDrop),
                baked = emptyList(),
            ),
            Case(
                TurnContextMode.NOT_SUPPORTED,
                TurnContextMode.NOT_SUPPORTED,
                kept = emptyList(),
                baked = listOf(escaped, untrustedBake),
            ),
            Case(
                TurnContextMode.KEPT_UNRENDERED,
                TurnContextMode.KEPT_UNRENDERED,
                kept = listOf(escaped, untrustedBake),
                baked = emptyList(),
            ),
            Case(
                TurnContextMode.TRANSIENT,
                TurnContextMode.NOT_SUPPORTED,
                kept = listOf(escaped, trustedDrop),
                baked = listOf(untrustedBake),
            ),
            Case(
                TurnContextMode.TRANSIENT,
                TurnContextMode.NOT_SUPPORTED,
                kept = listOf(escaped),
                baked = emptyList(),
                tainted = false,
                items = listOf(trustedBake, untrustedDrop),
            ),
        )
        for (case in cases) {
            val store = MemoryStore()
            val model = ScriptedModel(turnContextMode = case.trusted, untrustedTurnContextMode = case.untrusted)
                .reply { text("Hello") }
            val hook = Interceptor(TurnPoints.CONTEXT_INJECT) { context ->
                HookDecision.Replace((case.items ?: all).fold(context, TurnContext::plus))
            }
            lateinit var admission: Admission.Accepted

            val lines = logged {
                agentHarness(config, store) { channel().model(model).hook(hook) }.execute {
                    admission = channel().receive("Hi") as Admission.Accepted
                    admission.ticket.outcome()
                }
            }

            val name = "trusted ${case.trusted}, untrusted ${case.untrusted}"
            val request = model.requests.single()
            val opening = request.history.last() as UserEntry
            val stored = blocking { store.transcripts.entries(store.conversations.current(key).id) }
            assertEquals(listOf("Hi" to emptyList<TurnContextItem>()), hook.seen.map { it.text to it.items }, name)
            assertEquals(case.kept, request.turnContext, name)
            val baked = case.baked.map { ContextPart(it.source, it.text) }
            assertEquals(baked + TextPart("Hi"), opening.parts.drop(1), name)
            assertEquals(opening.parts, (stored.first() as UserEntry).parts, name)
            val taint = "DEBUG Turn ${admission.ticket.turn} of $key is tainted by untrusted turn context"
            assertEquals(case.tainted, taint in lines, name)
        }
    }
}
