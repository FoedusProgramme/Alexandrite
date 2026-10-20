package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
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
            val control = get<ChatLinks>()
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
