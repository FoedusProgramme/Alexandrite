package org.unitedsoftware.alexandrite.agent.trust

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TrustControlTest {
    private val store = MemoryStore()
    private val key = AgentChatKey.parse("coder@test:main:chat")

    private fun harness(): PluginHarness = agentHarness(
        """{"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}}""",
        store,
    ) { channel(operators = setOf("operator"), trusted = setOf("friend")).model(ScriptedModel()) }

    @Test
    fun `people are trusted per agent across restarts, never operators, and untrusted again`() {
        harness().execute {
            val control = get<TrustControl>()
            val chat = channel().chat()
            val operator = channel().user("operator")
            control.trustPerson(chat, channel().user("guest"), operator)

            assertFailsWith<ControlRefusedException> { control.trustPerson(chat, operator, operator) }
        }
        harness().execute {
            val control = get<TrustControl>()
            val chat = channel().chat()
            val trusted = control.people(chat).single()

            assertEquals(channel().user("guest") to channel().user("operator"), trusted.person to trusted.trustedBy)
            assertEquals(Revocation(true, false), control.untrustPerson(chat, channel().user("guest"), null))
            assertEquals(Revocation(false, true), control.untrustPerson(chat, channel().user("friend"), null))
            assertEquals(emptyList(), control.people(chat))
        }
    }

    @Test
    fun `the taint of a chat's conversation is read through the trust control`() {
        harness().execute {
            val control = get<TrustControl>()
            val clean = control.taint(channel().chat())
            val conversation = store.conversations.current(key).id
            store.conversations.markTainted(conversation, "a web page", Instant.EPOCH)

            assertNull(clean)
            assertEquals("a web page", control.taint(channel().chat())?.reason)
        }
    }
}
