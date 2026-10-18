package org.unitedsoftware.alexandrite.commands

import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.TestCommandContext
import org.unitedsoftware.alexandrite.testkit.testTexts
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal const val PLUGIN = "alexandrite-commands"

internal val CHINESE = LanguageTag("zh-CN")

internal val OPERATOR: ChatUser = testUser("operator", isOperator = true)

internal val GUEST: ChatUser = testUser("guest")

internal fun commandTexts(catalog: TextCatalog = TextCatalog.EMPTY): Texts = testTexts(PLUGIN, catalog = catalog)

internal fun commandContext(
    invocation: CommandInvocation,
    agent: AgentId = AgentId.MAIN,
    language: LanguageTag? = null,
): TestCommandContext {
    val turn = testTurn(TurnKind.COMMAND, invocation.chat) {
        actor(invocation.issuer)
        agent(agent)
        language(language)
    }
    return TestCommandContext(turn, invocation.trigger)
}

internal suspend fun CommandHandler.answer(
    invocation: CommandInvocation,
    context: TestCommandContext = commandContext(invocation),
): String {
    handle(invocation, context)
    return context.replies.single().text
}

internal class TestClock(var now: Instant = TEST_TIME) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}
