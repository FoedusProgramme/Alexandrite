package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.StoreIndex
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.worker.ChatIntakes
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomesTest {
    private data class Seen(val outcome: String?, val end: TurnEndKind?, val reply: String, val replayable: Boolean)

    /** How the turn that [act] submits ends. */
    private fun seen(
        stop: Boolean = false,
        unreadableHistory: Boolean = false,
        configure: PluginHarness.Builder.() -> Unit = {},
        script: ScriptedModel.() -> Unit = {},
        act: suspend PluginHarness.Running.() -> Admission.Accepted = { ask() },
    ): Seen {
        val store = MemoryStore()
        val model = ScriptedModel().apply(script)
        lateinit var channel: RecordingChannel
        lateinit var admission: Admission.Accepted
        agentHarness(CONFIG, store.takeUnless { unreadableHistory }) {
            channel(admins = setOf("admin")).model(model).apply(configure)
            if (unreadableHistory) plugin(StoreIndex(store, transcripts = Unreadable(store.transcripts)))
        }.execute {
            channel = channel()
            admission = act()
            if (stop) stop() else admission.ticket.outcome()
        }
        val outcome = blocking { admission.ticket.outcome() }
        val turn = admission.ticket.turn
        val reply = channel.replies.firstOrNull { it.turn.id == turn }
        val shown = when {
            reply == null -> "none"
            reply.completed != null -> "completed ${reply.completed?.kind.toString().lowercase()}"
            else -> "abandoned ${reply.abandoned}"
        }
        return Seen(
            outcome::class.simpleName,
            blocking {
                store.conversations.turn(turn)?.end
            },
            shown,
            outcome.replayable,
        )
    }

    @Test
    fun `each way a turn ends gives the outcome, recorded end, reply end and replay of the outcome table`() {
        val rows = linkedMapOf(
            "answered" to seen(script = { reply { text("Done.") } }),
            "ended on a notice" to seen(configure = { hook(stopper(TurnPoints.TURN_INPUT, "Not now.")) }),
            "joined a running turn" to run {
                val read = recordingTool("fs.read").hold()
                seen(configure = { tool(read) }, script = { reply { toolCall("fs.read") }.reply { text("Both.") } }) {
                    val first = ask("Read it.")
                    read.awaitCalls(1)
                    val second = ask("And this.")
                    read.release()
                    first.ticket.outcome()
                    second
                }
            },
            "blank in round 0" to seen(script = { reply { reasoning("Nothing.") } }),
            "refused in round 0" to seen(
                script = {
                    reply {
                        text("No.")
                        finish(FinishKind.REFUSAL)
                    }
                },
            ),
            "cancelled" to seen(
                script = {
                    reply {
                        text("Hel")
                        hang()
                    }
                },
            ) {
                ask().also {
                    channel().awaitEvent { event -> event is RecordingChannel.Event.Previewed }
                    it.ticket.cancel()
                }
            },
            "failed" to seen(script = { fail(ModelErrorKind.AUTHENTICATION) }),
            "failed, its notice blank in the catalog" to run {
                val blank = TextCatalog.builder().text("alexandrite-agent", LanguageTag("en"), "notice.failed", " ")
                seen(unreadableHistory = true, configure = { texts(blank.build()) })
            },
            "queued at a shutdown" to seen(stop = true, script = { reply { hang() } }) {
                ask()
                model().awaitRequests(1)
                ask()
            },
            "held at a shutdown" to seen(stop = true) {
                val other = channel().chat("other")
                get<ChatIntakes>().gate(other, TurnId("t-command"))
                channel().receive("Hi", other, channel().user("admin")) as Admission.Accepted
            },
            "cut off before it stored or showed anything" to seen(stop = true, script = { reply { hang() } }) {
                ask().also { model().awaitRequests(1) }
            },
            "cut off after a preview" to seen(
                stop = true,
                script = {
                    reply {
                        text("Hel")
                        hang()
                    }
                },
            ) { ask().also { channel().awaitEvent { it is RecordingChannel.Event.Previewed } } },
            "cut off after a stored round" to run {
                val read = recordingTool("fs.read").hold()
                seen(stop = true, configure = { tool(read) }, script = { reply { toolCall("fs.read") } }) {
                    ask().also { read.awaitCalls(1) }
                }
            },
        )

        val completed = TurnEndKind.COMPLETED
        val failed = TurnEndKind.FAILED
        val shutDown = TurnEndKind.SHUT_DOWN
        assertEquals(
            linkedMapOf(
                "answered" to Seen("Completed", completed, "completed reply", false),
                "ended on a notice" to Seen("Completed", completed, "completed notice", false),
                "joined a running turn" to Seen("Absorbed", null, "none", false),
                "blank in round 0" to Seen("TakenBack", TurnEndKind.TAKEN_BACK, "completed notice", false),
                "refused in round 0" to Seen("TakenBack", TurnEndKind.TAKEN_BACK, "completed notice", false),
                "cancelled" to Seen("Cancelled", TurnEndKind.CANCELLED, "abandoned cancelled", false),
                "failed" to Seen("Failed", failed, "completed notice", false),
                "failed, its notice blank in the catalog" to Seen("Failed", failed, "completed notice", false),
                "queued at a shutdown" to Seen("ShutDown", null, "none", true),
                "held at a shutdown" to Seen("ShutDown", null, "none", true),
                "cut off before it stored or showed anything" to Seen("ShutDown", shutDown, "abandoned shutdown", true),
                "cut off after a preview" to Seen("ShutDown", shutDown, "abandoned shutdown", false),
                "cut off after a stored round" to Seen("ShutDown", shutDown, "abandoned shutdown", false),
            ),
            rows,
        )
    }

    private fun PluginHarness.Running.ask(text: String = "Hi"): Admission.Accepted =
        channel().receive(text, sender = channel().user("admin")) as Admission.Accepted

    /** Transcripts whose conversations cannot be read. */
    private class Unreadable(store: TranscriptStore) : TranscriptStore by store {
        override suspend fun entries(conversation: ConversationId): List<TranscriptEntry> =
            throw IllegalStateException("The disk is gone.")
    }

    private companion object {
        val CONFIG = """
            {
              "previewIntervalMillis": 0,
              "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
        """.trimIndent()
    }
}
