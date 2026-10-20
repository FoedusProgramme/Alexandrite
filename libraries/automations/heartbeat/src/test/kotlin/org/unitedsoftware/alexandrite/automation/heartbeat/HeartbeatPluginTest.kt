package org.unitedsoftware.alexandrite.automation.heartbeat

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingTurnInitiator
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testCommandContext
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class HeartbeatPluginTest {
    private val coder = AgentId("coder")
    private val time = ManualTime()
    private val initiator = RecordingTurnInitiator(outcome = TurnOutcome.Completed(null))
    private val control = RecordingAgent(coder).apply { scriptAgent(coder, name = "Coder") }

    private fun harness(config: String?, initiator: RecordingTurnInitiator = this.initiator): PluginHarness =
        heartbeatHarness(config) { plugin(TimePlugin(time)).turnInitiator(initiator).agent(control).channel() }

    /**
     * Moves time on by [by], then waits until [sleepers] beat loops sleep again and [beats] turns were initiated, and
     * gives a beat that ended a moment to wind down.
     */
    private suspend fun step(
        by: Duration,
        beats: Int,
        sleepers: Int = 1,
        initiator: RecordingTurnInitiator = this.initiator,
    ) {
        val before = initiator.initiated.size
        time.advance(by)
        time.settle(sleepers)
        withTimeout(WAIT) { initiator.awaitInitiated(beats) }
        assertEquals(beats, initiator.initiated.size, "beats at ${time.now()}")
        if (beats > before) delay(WIND_DOWN)
    }

    private fun at(offset: Duration): Instant = ORIGIN.plus(offset.toJavaDuration())

    private suspend fun PluginHarness.Running.heartbeat(arguments: String = ""): String {
        val handler = getAll<CommandHandler>().single { handler -> handler.commands.any { it.name == "heartbeat" } }
        val invocation = testCommand("heartbeat", arguments)
        val context = testCommandContext(invocation)
        handler.handle(invocation, context)
        return context.replies.single().text
    }

    // Beats.

    @Test
    fun `each agent beats every interval, the first an interval after the start, in a trusted turn of no chat`() {
        val main = AgentId("main")
        val config = """
            {"agents": {"coder": {"every": "30m"}, "main": {"every": "1h", "prompt": "Check the backups."}}}
        """.trimIndent()

        harness(config).execute {
            time.settle(2)
            step(29.minutes, 0, sleepers = 2)
            step(1.minutes, 1, sleepers = 2)
            step(30.minutes, 3, sleepers = 2)
            step(30.minutes, 4, sleepers = 2)
        }

        val turns = initiator.initiated.map { it.turn }
        assertEquals(
            listOf(coder, setOf(coder, main), coder),
            listOf(turns[0].agent, turns.slice(1..2).map { it.agent }.toSet(), turns[3].agent),
        )
        for (initiated in initiator.initiated) {
            val turn = initiated.turn
            val agent = checkNotNull(turn.agent)
            assertEquals("alexandrite-automation-heartbeat", initiated.plugin)
            assertEquals(
                listOf(ChatAddress.ofAgent(agent), TurnKind.HEARTBEAT, Trust.TRUSTED, ReplyRoute.None, null),
                listOf(turn.chat, turn.kind, turn.trust, turn.route, turn.mandate),
            )
            assertEquals(if (agent == coder) DEFAULT_PROMPT else "Check the backups.", turn.prompt)
        }
    }

    @Test
    fun `beats outside the active hours are skipped, in the heartbeat's zone or the host's, also across midnight`() {
        val night = AgentId("night")
        val berlin = ZoneId.of("Europe/Berlin")
        val config = """
            {
              "agents": {
                "coder": {"every": "1h", "activeHours": "11:00-13:00"},
                "night": {"every": "1h", "activeHours": "22:00-06:00", "zone": "Europe/Berlin"}
              }
            }
        """.trimIndent()
        val expected = mutableListOf<Pair<AgentId, Int>>()

        harness(config).execute {
            time.settle(2)
            for (hour in 1..24) {
                val at = ORIGIN.plus(hour.hours.toJavaDuration())
                val utc = at.atZone(ZoneOffset.UTC).hour
                if (utc in 11..12) expected += coder to utc
                val local = at.atZone(berlin).hour
                if (local >= 22 || local < 6) expected += night to local
                step(1.hours, expected.size, sleepers = 2)
            }
        }

        assertEquals(listOf(11, 12), expected.filter { it.first == coder }.map { it.second })
        assertEquals(listOf(22, 23, 0, 1, 2, 3, 4, 5), expected.filter { it.first == night }.map { it.second })
        assertEquals(
            expected.map { it.first }.groupingBy { it }.eachCount(),
            initiator.initiated.map { checkNotNull(it.turn.agent) }.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `a beat still running when the next is due skips it`() {
        val pending = RecordingTurnInitiator()

        harness("""{"agents": {"coder": {"every": "30m"}}}""", pending).execute {
            time.settle(1)
            step(30.minutes, 1, initiator = pending)
            step(30.minutes, 1, initiator = pending)
            step(30.minutes, 1, initiator = pending)
            pending.tickets.single().complete(TurnOutcome.Completed(null))
            delay(WIND_DOWN)
            step(30.minutes, 2, initiator = pending)
        }
    }

    @Test
    fun `a refused beat is logged once per agent and reason until a beat runs`() {
        initiator.refuseNext(RefusalReason.QUEUE_FULL)
        initiator.refuseNext(RefusalReason.QUEUE_FULL)
        initiator.refuseNext(RefusalReason.UNKNOWN_CHAT)
        initiator.refuseNext(RefusalReason.UNKNOWN_CHAT)
        initiator.refuseNext(RefusalReason.QUEUE_FULL)
        val lines = logged {
            harness("""{"agents": {"coder": {"every": "30m"}}}""").execute {
                time.settle(1)
                for (beat in 1..7) step(30.minutes, beat)
                initiator.refuseNext(RefusalReason.QUEUE_FULL)
                step(30.minutes, 8)
            }
        }

        val warnings = lines.filter { "home" !in it }
        assertEquals(3, warnings.size, "$warnings")
        assertContains(warnings[0], "queue_full")
        assertContains(warnings[1], "no configured agent")
        assertContains(warnings[2], "queue_full")
    }

    // Config.

    @Test
    fun `without a heartbeat nothing beats, nothing is logged and the command says so`() {
        val lines = logged {
            harness(null).execute {
                time.advance(3.hours)
                delay(100.milliseconds)
                assertEquals("Coder has no heartbeat.", heartbeat())
            }
        }

        assertEquals(emptyList(), initiator.initiated)
        assertEquals(emptyList(), lines)
    }

    @Test
    fun `an invalid heartbeat fails the start, naming its path`() {
        val cases = mapOf(
            """{"every": "2m"}""" to "agents.coder.every must be 5m at least, was 2m",
            """{"every": "half an hour"}""" to "is no duration; write minutes and up",
            """{"every": "30m", "prompt": " "}""" to "agents.coder.prompt may not be blank",
            """{"every": "30m", "activeHours": "8-12"}""" to "agents.coder.activeHours must be written HH:mm-HH:mm",
            """{"every": "30m", "zone": "Mars/Olympus"}""" to "agents.coder.zone is no time zone",
            """{"prompt": "Look."}""" to "every",
        )

        for ((heartbeat, message) in cases) {
            val error = assertFailsWith<RuntimeStartException>(heartbeat) {
                runBlocking { harness("""{"agents": {"coder": $heartbeat}}""").run {} }
            }
            val problem = error.problems.single()
            assertEquals("runtime.invalid_config", problem.kind.id)
            assertContains(problem.message, "Invalid config at 'automations.heartbeat")
            assertContains(problem.message, message)
        }
        val error = assertFailsWith<RuntimeStartException> {
            runBlocking { harness("""{"agents": {"Coder!": {"every": "1h"}}}""").run {} }
        }
        assertContains(error.problems.single().message, "agents.Coder! is no agent id")
    }

    @Test
    fun `a changed, added or removed heartbeat takes effect at once and needs no restart`(@TempDir root: Path) {
        val main = AgentId("main")
        val changes = mutableListOf<ConfigChanges>()

        heartbeatHarness("""{"agents": {"coder": {"every": "30m"}}}""") {
            plugin(TimePlugin(time)).turnInitiator(initiator).agent(control).channel()
            configFile(root.resolve("alexandrite.json"))
        }.execute {
            val host = get<HostConfig>()
            time.settle(1)
            step(30.minutes, 1)

            changes += host.edit { set("automations.heartbeat.agents.coder.every", JsonPrimitive("45m")) }
            time.sleeping(at(45.minutes))
            assertContains(heartbeat(), "every 45 min")
            step(15.minutes, 2)
            step(30.minutes, 2)
            step(15.minutes, 3)

            changes += host.edit {
                set("automations.heartbeat.agents.main", Json.parseToJsonElement("""{"every": "1h"}"""))
            }
            time.sleeping(at(2.hours), at(135.minutes))
            changes += host.edit { remove("automations.heartbeat.agents.coder") }
            time.sleeping(at(2.hours))
            step(30.minutes, 4)
            step(15.minutes, 4)

            val refused = assertFailsWith<ConfigEditException> {
                host.edit { set("automations.heartbeat.agents.main.every", JsonPrimitive("2m")) }
            }
            assertContains(refused.message.orEmpty(), "agents.main.every must be 5m at least")
            step(45.minutes, 5)
        }

        val live = { path: String -> ConfigChanges(listOf("automations.heartbeat.agents.$path"), emptyList()) }
        assertEquals(listOf(live("coder.every"), live("main"), live("coder")), changes)
        assertEquals(listOf(coder, coder, coder, main, main), initiator.initiated.map { it.turn.agent })
    }

    // The home chat.

    @Test
    fun `an agent with a heartbeat and no home chat gets a warning at the start, and one with a home none`() {
        val homeless = logged { harness("""{"agents": {"coder": {"every": "30m"}}}""").execute {} }
        control.declareHome(coder, testChat("home"))
        val housed = logged { harness("""{"agents": {"coder": {"every": "30m"}}}""").execute {} }

        assertContains(homeless.single(), "Agent 'coder' has a heartbeat but no home chat")
        assertEquals(emptyList(), housed)
    }

    // The command.

    @Test
    fun `the command shows the heartbeat of the chat's agent, when it beats next and whether it has no home`() {
        val config = """
            {"agents": {"coder": {"every": "1h30m", "activeHours": "08:00-23:00", "zone": "Asia/Shanghai"}}}
        """.trimIndent()
        val noHome =
            "Coder has no home chat, so a beat reaches nobody through \"home\". An operator makes a chat its " +
                "home with /home there, or the config sets channels.<instance>.home for the agent."

        harness(config).execute {
            time.settle(1)
            val first = heartbeat()
            control.declareHome(coder, testChat("home"))
            val housed = heartbeat()
            step(4.hours + 30.minutes, 1)
            val late = heartbeat()

            assertEquals(
                "Heartbeat of Coder: every 1 h 30 min, from 08:00 to 23:00 (Asia/Shanghai).\n" +
                    "Next beat: 2026-10-18 19:30.\n$noHome",
                first,
            )
            assertEquals(first.removeSuffix("\n$noHome"), housed)
            assertEquals(
                "Heartbeat of Coder: every 1 h 30 min, from 08:00 to 23:00 (Asia/Shanghai).\n" +
                    "Next beat: 2026-10-19 09:00.",
                late,
            )
            assertEquals("Send /heartbeat without anything after it.", heartbeat("now"))
        }
        val fresh = ManualTime()
        heartbeatHarness("""{"agents": {"coder": {"every": "2h"}}}""") {
            plugin(TimePlugin(fresh)).turnInitiator(initiator).agent(control).channel()
        }.execute {
            fresh.settle(1)
            assertContains(heartbeat(), "every 2 h, all day (UTC)")
        }
    }
}
