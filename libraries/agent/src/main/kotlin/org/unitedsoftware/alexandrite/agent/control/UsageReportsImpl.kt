package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.usage.UsageSums
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.UsageReport
import org.unitedsoftware.alexandrite.sdk.turn.UsageReports

@Singleton
@Binds(UsageReports::class)
internal class UsageReportsImpl(private val runner: ControlRunner, private val sums: UsageSums) : UsageReports {
    override suspend fun report(chat: ChatAddress, everyAgent: Boolean): UsageReport =
        sums.report(runner.key(chat).agent, chat, everyAgent)
}
