package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.assertStartFails
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CommandRegistryTest {
    private class Handler(vararg names: String) : CommandHandler {
        override val commands = names.map { CommandSpec.builder(it, "Does $it.").build() }

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {}
    }

    private class Other(vararg names: String) : CommandHandler {
        override val commands = names.map { CommandSpec.builder(it, "Does $it.").build() }

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {}
    }

    @Test
    fun `a command goes to the handler that claims its name, ignoring case`() {
        val control = Handler("new", "cancel")
        val settings = Other("model", "agent")

        agentHarness { commandHandler(control).commandHandler(settings) }.execute {
            val registry = get<CommandRegistry>()

            assertSame(control, registry.handler("new"))
            assertSame(control, registry.handler("CANCEL"))
            assertSame(settings, registry.handler("Model"))
            assertNull(registry.handler("help"))
            assertEquals(listOf("agent", "cancel", "model", "new"), registry.commands.map { it.name })
        }
    }

    @Test
    fun `two handlers that claim one command fail the start`() {
        agentHarness { commandHandler(Handler("new", "cancel")).commandHandler(Other("model", "new")) }
            .assertStartFails(
                StartStage.GRAPH,
                "Command 'new' is claimed by both ${Handler::class.java.name} and ${Other::class.java.name}: switch " +
                    "off the plugin of one of them.",
            )
    }

    @Test
    fun `one handler that claims a command twice fails the start`() {
        agentHarness { commandHandler(Handler("new", "new")) }.assertStartFails(
            StartStage.GRAPH,
            "Command 'new' is claimed by both ${Handler::class.java.name} and ${Handler::class.java.name}: switch " +
                "off the plugin of one of them.",
        )
    }
}
