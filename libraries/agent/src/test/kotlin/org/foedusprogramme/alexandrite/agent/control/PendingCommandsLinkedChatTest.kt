package org.foedusprogramme.alexandrite.agent.control

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PendingCommandsLinkedChatTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val coder = AgentId("coder")
    private val anchor = ChatAddress.parse("test:main:1")
    private val member = ChatAddress.parse("test:side:2")

    private fun harness(): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {"default": true}, "test:side": {}}
            }
          }
        }
        """.trimIndent(),
        store,
    ) { channel().channel("side").model(model).commandHandler(Noted()) }

    @Test
    fun `a command run in a linked chat is recorded on the anchor's conversation`() {
        harness().execute {
            val control = get<AgentControl>()
            control.link(coder, anchor, member, null)

            val side = channel(member.instance)
            assertIs<Admission.Accepted>(side.receiveCommand("noted", "From the side.", member, side.user())).ticket
                .outcome()
        }

        val anchorKey = AgentChatKey(coder, anchor)
        val conversation = blocking { store.conversations.current(anchorKey).id }
        val recorded = blocking { store.records.list("alexandrite-agent", "pending_commands") }
        assertEquals(listOf("$conversation"), recorded.map { it.key })
    }
}

/** `/noted <text>` replies with it. */
private class Noted : CommandHandler {
    override val commands: List<CommandSpec> = listOf(CommandSpec.builder("noted", "Test command /noted.").build())

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        context.reply(invocation.arguments)
    }
}
