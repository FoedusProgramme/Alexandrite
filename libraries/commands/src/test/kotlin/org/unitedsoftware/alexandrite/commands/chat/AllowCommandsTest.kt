package org.unitedsoftware.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.TestClock
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingAgent.Call
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class AllowCommandsTest {
    private val control = RecordingAgent()
    private val commands = AllowCommands(control.rules, commandTexts(), TestClock())

    private val status = RuleSubject("command.exec", listOf("git", "status"), "git status")
    private val gradle = RuleSubject("command.exec", listOf("/srv/repo/gradlew", "test"), "/srv/repo/gradlew test")

    private suspend fun allow(arguments: String = ""): String =
        commands.answer(testCommand("allow", arguments, issuer = OPERATOR))

    @Test
    fun `only an operator may list or revoke allow rules`() {
        assertEquals(CommandForms.ALL, commands.commands.single().operatorsOnly)
    }

    @Test
    fun `allow lists the declared rules, then the conversation's with who allowed them and when`() = runTest {
        val empty = allow()
        control.declareAllowRule(status)
        control.declareAllowRule(RuleSubject("deploy.run", emptyList(), "deploy.run"))
        control.grantAllowRule(testChat(), gradle, OPERATOR)
        control.grantAllowRule(testChat(), RuleSubject("notes.add", listOf("todo"), "todo"), null)
        control.grantAllowRule(testChat("other"), RuleSubject("command.exec", listOf("make"), "make"), OPERATOR)

        assertEquals("No allow rule is in force here.", empty)
        assertEquals(
            "Allow rules in force here (revoke one with /allow revoke <number>):\n" +
                "1. command.exec: git status (declared in the config file)\n" +
                "2. deploy.run: every call (declared in the config file)\n" +
                "3. command.exec: /srv/repo/gradlew test (allowed by Operator on 2026-01-01 until /new)\n" +
                "4. notes.add: todo (allowed by the host on 2026-01-01 until /new)",
            allow(),
        )
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `allow revoke removes a granted rule, and says which rules only the config file removes`() = runTest {
        control.declareAllowRule(status)
        control.grantAllowRule(testChat(), gradle, OPERATOR)
        control.grantAllowRule(
            testChat(),
            RuleSubject("command.exec", listOf("git", "status", "--short"), "x"),
            OPERATOR,
        )

        val replies = listOf(allow("revoke 3"), allow("REVOKE 2"), allow("revoke 1"), allow("revoke 2"))

        assertEquals(
            listOf(
                "Rule 3 is revoked, but the config file still allows its calls: edit the file to stop that.",
                "Rule 2 is revoked: its calls need an approval again.",
                "Rule 1 is declared in the config file: edit the file to remove it.",
                "There is no rule 2 here. Send /allow to see the rules.",
            ),
            replies,
        )
        assertEquals(listOf(3, 2, 1, 2).map { Call.RevokeAllowRule(testChat(), it, OPERATOR) }, control.calls)
    }

    @Test
    fun `anything but a bare allow or one revoke with a rule number changes nothing`() = runTest {
        val usage = "Send /allow to see the allow rules in force here, or /allow revoke <number> to revoke one."

        val replies = listOf("revoke", "revoke x", "revoke 0", "revoke -1", "3", "revoke 1 2", "forget 1").map {
            allow(it)
        }

        assertEquals(List(7) { usage }, replies)
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `a refused revocation replies with the agent's message`() = runTest {
        control.refuseNext("No agent serves the chat test:main:chat.")

        assertEquals("No agent serves the chat test:main:chat.", allow("revoke 1"))
    }
}
