package org.foedusprogramme.alexandrite.runtime.turn

import org.foedusprogramme.alexandrite.runtime.Probe
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.probe
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiation
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiator
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnInitiatorTest {
    @TempDir
    lateinit var dataDir: Path

    private val chat = ChatAddress(ChannelInstanceId(ChannelType("chan"), "work"), "-100")
    private val heartbeat = InitiatedTurn.builder(chat, TurnKind.HEARTBEAT, "Nobody has written for two hours.").build()

    private class Agent(val beat: Beat) : TurnInitiation {
        val initiated: MutableList<Pair<String, InitiatedTurn>> = Collections.synchronizedList(mutableListOf())

        override fun initiate(plugin: String, turn: InitiatedTurn): Admission {
            initiated += plugin to turn
            return Admission.Refused(RefusalReason.QUEUE_FULL, 20)
        }
    }

    private class Beat(val initiator: TurnInitiator)

    /** A plugin whose agent depends on the plugin `beat`, whose component injects its own initiator. */
    private fun agent() = TestIndex(
        "agent",
        bindings = listOf(
            binding(
                key<TurnInitiation>(),
                "agent",
                "Agent",
                dependencies = listOf(Dependency(key<Beat>(), DependencyKind.INSTANCE, "beat")),
            ) { r -> Agent(r.get(key<Beat>())) },
            probe("agent", "agent" to key<TurnInitiation>()),
        ),
    )

    private fun beat(id: String) = TestIndex(
        id,
        bindings = listOf(
            binding(
                key<Beat>(if (id == "beat") null else id),
                id,
                "Beat",
                dependencies = listOf(Dependency(key<TurnInitiator>(id), DependencyKind.INSTANCE, "initiator")),
            ) { r -> Beat(r.get(key<TurnInitiator>(id))) },
        ),
    )

    @Test
    fun `each plugin's initiator hands its turns to the agent in the plugin's name`() {
        spec(explicit(agent(), beat("beat"), beat("other")), dataDir).execute {
            val resolver = services.resolver()
            val agent = services.get(key<Probe>()).values.getValue("agent") as Agent

            val admission = agent.beat.initiator.initiate(heartbeat)
            resolver.get(key<Beat>("other")).initiator.initiate(heartbeat)

            assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 20), admission)
            assertEquals(listOf("beat" to heartbeat, "other" to heartbeat), agent.initiated.toList())
        }
    }

    @Test
    fun `without an agent an initiator refuses every turn`() {
        lateinit var initiator: TurnInitiator

        spec(explicit(beat("beat")), dataDir).execute {
            initiator = services.resolver().get(key<Beat>()).initiator

            assertEquals(Admission.Refused(RefusalReason.NO_AGENT), initiator.initiate(heartbeat))
        }

        assertEquals(Admission.Refused(RefusalReason.NO_AGENT), initiator.initiate(heartbeat))
    }

    @Test
    fun `an initiator first used after the runtime stopped refuses as shutting down`() {
        lateinit var initiator: TurnInitiator

        spec(explicit(agent(), beat("beat"), beat("other")), dataDir).execute {
            initiator = services.resolver().get(key<Beat>("other")).initiator
        }

        assertEquals(Admission.Refused(RefusalReason.SHUTTING_DOWN), initiator.initiate(heartbeat))
    }
}
