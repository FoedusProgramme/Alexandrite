package org.foedusprogramme.alexandrite.commands

import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.testkit.TEST_TIME
import org.foedusprogramme.alexandrite.testkit.TestCommandContext
import org.foedusprogramme.alexandrite.testkit.testTexts
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUser
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal const val PLUGIN = "alexandrite-commands"

internal val CHINESE = LanguageTag("zh-CN")

internal val ADMIN: ChatUser = testUser("admin", isAdmin = true)

internal val MEMBER: ChatUser = testUser("member")

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
