package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.testkit.RecordingTurnInitiator
import org.unitedsoftware.alexandrite.testkit.TestRecords
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateWatchTest {
    private val records = TestRecords()
    private val initiator = RecordingTurnInitiator("alexandrite-app")
    private val clock = MovingClock(Instant.parse("2026-10-20T08:00:00Z"))
    private val activity = Activity()
    private val updates = mutableListOf<String>()
    private var latest: String? = "1.0.0-alpha.2"

    private fun watch(
        version: String = "1.0.0-alpha.1",
        settings: UpdatesConfig = UpdatesConfig(),
        scripted: Boolean = true,
    ) = UpdateWatch(
        version,
        settings,
        { latest },
        "https://releases.test",
        activity,
        initiator,
        records.of("alexandrite-app"),
        if (scripted) { release -> updates.add(release) } else null,
        clock,
    )

    private val turns: List<InitiatedTurn> get() = initiator.initiated.map { it.turn }

    @Test
    fun `a newer release is told to the default agent once, also after a restart`() {
        runBlocking {
            val watch = watch()
            watch.tick()
            watch.tick()
            watch().tick()
        }

        val turn = turns.single()
        assertEquals(TurnKind.HEARTBEAT, turn.kind)
        assertEquals(Trust.TRUSTED, turn.trust)
        assertEquals(AgentId("coder"), turn.agent)
        assertTrue(turn.prompt.startsWith("Alexandrite 1.0.0-alpha.2 is available; this host runs 1.0.0-alpha.1."))
        assertTrue(turn.prompt.contains("https://releases.test/tag/v1.0.0-alpha.2"), turn.prompt)
        assertTrue(turn.prompt.contains("alexandrite update"), turn.prompt)
        assertTrue(turn.prompt.contains("chat.send"), turn.prompt)
        assertEquals(emptyList(), updates)
    }

    @Test
    fun `no release, an older one or no agent tells nobody, and a refused turn is tried again`() {
        runBlocking {
            latest = null
            watch().tick()
            latest = "1.0.0-alpha.1"
            watch().tick()
            latest = "1.0.0-alpha.2"
            activity.agent = null
            watch().tick()
            activity.agent = AgentId("coder")
            initiator.refuseNext(RefusalReason.QUEUE_FULL)
            watch().tick()
            watch().tick()
        }

        assertEquals(2, initiator.initiated.size)
        assertEquals(1, initiator.results.count { it is Admission.Accepted })
    }

    @Test
    fun `an automatic update waits until nobody wrote for the idle time and no turn or job runs`() {
        runBlocking {
            val watch = watch(settings = UpdatesConfig(install = true, idleMinutes = 120))
            watch.tick()
            clock.advance(119)
            watch.tick()
            assertEquals(emptyList(), updates)
            activity.lastMessage = clock.instant()
            clock.advance(60)
            watch.tick()
            assertEquals(emptyList(), updates)
            clock.advance(61)
            activity.busy = true
            watch.tick()
            assertEquals(emptyList(), updates)
            activity.busy = false
            watch.tick()
            watch.tick()
        }

        assertEquals(listOf("1.0.0-alpha.2"), updates)
        assertTrue(turns.single().prompt.contains("installs it by itself once nobody has written for 120 minutes"))
    }

    @Test
    fun `an instance the install script did not install, or one that does not ask for it, never updates itself`() {
        runBlocking {
            val unscripted = watch(settings = UpdatesConfig(install = true), scripted = false)
            val unasked = watch()
            clock.advance(600)
            unscripted.tick()
            unasked.tick()
        }

        assertEquals(emptyList(), updates)
        assertTrue(turns.none { it.prompt.contains("by itself") })
    }

    @Test
    fun `the new version tells the agent once what it was updated from`() {
        runBlocking {
            val old = watch(settings = UpdatesConfig(install = true, idleMinutes = 1))
            clock.advance(2)
            old.tick()
            old.tick()
            assertEquals(listOf("1.0.0-alpha.2"), updates)
            assertEquals(1, turns.size)
            watch(version = "1.0.0-alpha.2").tick()
            watch(version = "1.0.0-alpha.2").tick()
        }

        val told = turns.drop(1).single()
        assertEquals(TurnKind.HEARTBEAT, told.kind)
        assertTrue(told.prompt.startsWith("Alexandrite updated itself from 1.0.0-alpha.1 to 1.0.0-alpha.2"))
        assertTrue(told.prompt.contains("https://releases.test/tag/v1.0.0-alpha.2"), told.prompt)
    }

    @Test
    fun `an update that did not take is told to nobody and not tried again`() {
        runBlocking {
            val settings = UpdatesConfig(install = true, idleMinutes = 1)
            val first = watch(settings = settings)
            clock.advance(2)
            first.tick()
            val restarted = watch(settings = settings)
            clock.advance(2)
            restarted.tick()
            restarted.tick()
        }

        assertEquals(listOf("1.0.0-alpha.2"), updates)
        assertEquals(1, turns.size)
    }

    @Test
    fun `the update outlives the app in a systemd unit or a process group of its own`() {
        val command = listOf("sh", "install.sh", "--update")

        assertEquals(
            listOf("systemd-run", "--user", "--collect", "--quiet") + command,
            detached(command, Platform.UNIX, mapOf("INVOCATION_ID" to "0123")),
        )
        assertEquals(
            listOf("sh", "-c", "set -m; \"\$@\" &", "sh") + command,
            detached(command, Platform.MAC, emptyMap()),
        )
        assertEquals(command, detached(command, Platform.WINDOWS, emptyMap()))
    }

    private class Activity : AgentActivity {
        var agent: AgentId? = AgentId("coder")
        var busy = false
        override var lastMessage: Instant? = null

        override fun defaultAgent(): AgentId? = agent

        override suspend fun busy(): Boolean = busy
    }

    private class MovingClock(private var now: Instant) : Clock() {
        fun advance(minutes: Long) {
            now = now.plusSeconds(minutes * 60)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }
}
