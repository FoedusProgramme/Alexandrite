@file:OptIn(InternalAutomationApi::class)

package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.unitedsoftware.alexandrite.automation.common.InternalAutomationApi
import org.unitedsoftware.alexandrite.automation.common.TimeSource
import org.unitedsoftware.alexandrite.automation.schedule.AlexandriteAutomationScheduleIndex
import org.unitedsoftware.alexandrite.automation.schedule.ScheduleControl
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Scheduled tasks over a fake Bot API, on a clock the test moves. */
class TelegramScheduleTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()
    private val time = ManualTime(Instant.parse("2026-10-18T10:00:00Z"))

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `an admin schedules in a group, the task runs as them there, and the group lists and cancels its tasks`() {
        app.model.reply { toolCall("schedule.create", """{"prompt": "Remind the group of the build.", "in": "1m"}""") }
            .reply { toolCall("schedule.create", """{"prompt": "Post the daily summary.", "every": "1d"}""") }
            .reply { text("Both are scheduled.") }
            .reply { text("Time to check the build.") }
        val builder = app.builder(directory)
            .plugin(AlexandriteAutomationScheduleIndex())
            .plugin(TimePlugin(time))
            .config(AlexandriteAutomationScheduleIndex(), """{"minIntervalMinutes": 60}""")

        app.run(builder) {
            app.send(app.ada, app.group, "$BOT_MENTION remind us in a minute, and post a summary daily")
            app.message(app.group, "Both are scheduled.")
            idle()
            val (once, daily) = get<ScheduleControl>().list(app.key(app.group))

            time.advance(61)
            app.message(app.group, "Time to check the build.")
            idle()
            app.send(app.bob, app.group, "/schedule")
            val listed = app.bodyMessages(app.group, 3).last().text
            app.send(app.bob, app.group, "/schedule cancel ${daily.id}")
            app.bodyMessages(app.group, 4)
            app.send(app.ada, app.group, "/schedule cancel ${daily.id}")
            app.message(app.group, "Cancelled task ${daily.id}.")

            val reminder = app.model.requests.last().history.filterIsInstance<UserEntry>().last()
                .parts.filterIsInstance<ContextPart>().joinToString("\n") { it.text }
            assertContains(reminder, "On behalf of: Ada")
            assertContains(app.model.requests.last().history.toString(), "Scheduled task ${once.id}, set by Ada")
            assertContains(listed, "${daily.id}: every 1d, next 2026-10-19 10:00 +00:00, set by Ada")
            assertEquals(emptyList(), get<ScheduleControl>().list(app.key(app.group)))
        }

        val texts = app.fake.botMessages(TelegramApp.GROUP).body().map { it.text }
        assertEquals(listOf("Both are scheduled.", "Time to check the build."), texts.take(2))
        assertEquals("Tasks scheduled in this chat:", texts[2].lines().first())
        assertEquals("Only an admin of this bot may use /schedule.", texts[3])
    }

    @Test
    fun `a notice that quotes a task's prompt is not vouched for, so a reply quoting it marks the conversation`() {
        app.model.reply { toolCall("schedule.create", """{"prompt": "Remind Ada of the build.", "in": "1m"}""") }
            .reply { text("Scheduled.") }
            .reply { text("Fine.") }
        val builder = app.builder(directory, sqlite = true)
            .plugin(AlexandriteAutomationScheduleIndex())
            .plugin(TimePlugin(time))
            .config(AlexandriteAutomationScheduleIndex(), """{"missedWindowHours": 1}""")
        var taint: ConversationTaint? = null

        app.run(builder) {
            app.send(app.ada, app.adaChat, "Remind me of the build in a minute.")
            app.message(app.adaChat, "Scheduled.")
            idle()
        }
        time.advance(3 * 3600)
        app.run(builder) {
            val notice = app.fake.await(TelegramApp.WAIT) {
                botMessages(TelegramApp.ADA).firstOrNull { it.text.startsWith("Scheduled task ") }
            }
            assertContains(notice.text, "Remind Ada of the build.")
            app.fake.send(app.ada, app.adaChat, "Test.", replyTo = notice.id)
            app.message(app.adaChat, "Fine.")
            idle()
            taint = get<ConversationStore>().taint(get<ConversationStore>().current(app.key(app.adaChat)).id)
        }

        assertEquals("a quote that its channel does not vouch for", taint?.reason)
    }
}

/** A clock that stands still until [advance] moves it. */
private class ManualTime(start: Instant) : TimeSource {
    private val now = MutableStateFlow(start)

    override fun now(): Instant = now.value

    override suspend fun sleepUntil(instant: Instant) {
        now.first { it >= instant }
    }

    fun advance(seconds: Long) {
        now.value = now.value.plusSeconds(seconds)
    }
}

/** A plugin that binds [time] as the schedule's clock. */
private class TimePlugin(time: TimeSource) : PluginIndex {
    override val info: PluginInfo =
        PluginInfo(ID, "Test time", "test", "", AlexandriteSdk.API_VERSION, emptyList(), javaClass.name)
    override val configRoot: String = PluginIds.thirdPartyRoot(ID)
    private val bindings = listOf(instanceBinding(key<TimeSource>(), time, ID, "TimeSource"))

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()

    override fun bindings(): List<Binding<*>> = bindings

    private companion object {
        const val ID = "test-time"
    }
}
