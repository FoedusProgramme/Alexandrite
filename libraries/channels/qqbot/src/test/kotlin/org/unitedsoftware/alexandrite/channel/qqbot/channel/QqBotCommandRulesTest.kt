package org.unitedsoftware.alexandrite.channel.qqbot.channel

import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QqBotCommandRulesTest {
    private val instance = ChannelInstanceId(ChannelType("qqbot"), "main")
    private val direct = ChatAddress(instance, "private:u1")
    private val group = ChatAddress(instance, "group:g1")
    private val rules = QqBotCommandRules(listOf(Commands), control = null)

    @Test
    fun `mayRun answers the matrix for a member`() {
        assertTrue(rules.mayRun(direct, admin = false, "model", withArguments = false))
        assertFalse(rules.mayRun(direct, admin = false, "model", withArguments = true))
        assertTrue(rules.mayRun(direct, admin = false, "help", withArguments = false))
        assertTrue(rules.mayRun(group, admin = false, "help", withArguments = false))
        assertTrue(rules.mayRun(group, admin = false, "context", withArguments = true))
        assertFalse(rules.mayRun(group, admin = false, "agent", withArguments = true))
        assertFalse(rules.mayRun(group, admin = false, "trust", withArguments = false))
        assertTrue(rules.mayRun(group, admin = true, "trust", withArguments = false))
        assertTrue(rules.mayRun(group, admin = false, "not_a_command", withArguments = true))
        assertTrue(rules.mayRun(group, admin = false, "heartbeat", withArguments = false))
        assertFalse(rules.mayRun(direct, admin = false, "schedule", withArguments = true))
        assertTrue(rules.mayRun(direct, admin = false, "timezone", withArguments = true))
        assertFalse(rules.mayRun(group, admin = false, "timezone", withArguments = true))
        assertFalse(rules.mayRun(direct, admin = false, "home", withArguments = false))
    }

    private object Commands : CommandHandler {
        override val commands: List<CommandSpec> = listOf(
            "help",
            "settings",
            "context",
            "agent",
            "model",
            "trust",
            "schedule",
            "heartbeat",
            "timezone",
            "home",
            "link",
            "unlink",
            "untaint",
        ).map { CommandSpec.builder(it, "a test command").build() }

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) = Unit
    }
}
