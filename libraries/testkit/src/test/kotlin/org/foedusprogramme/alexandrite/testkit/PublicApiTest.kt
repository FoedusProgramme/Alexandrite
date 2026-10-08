@file:OptIn(ExperimentalCompilerApi::class)

package org.foedusprogramme.alexandrite.testkit

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicApiTest {
    @TempDir
    lateinit var workingDir: File

    /** A third-party plugin's test that uses every double, compiled without the internal API. */
    private val sample = """
        import kotlinx.coroutines.flow.toList
        import kotlinx.serialization.json.JsonObject
        import org.foedusprogramme.alexandrite.sdk.channel.*
        import org.foedusprogramme.alexandrite.sdk.chat.*
        import org.foedusprogramme.alexandrite.sdk.di.key
        import org.foedusprogramme.alexandrite.sdk.model.*
        import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
        import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
        import org.foedusprogramme.alexandrite.sdk.transcript.*
        import org.foedusprogramme.alexandrite.sdk.turn.*
        import org.foedusprogramme.alexandrite.testkit.*
        import java.nio.file.Path
        import java.time.ZoneOffset
        import kotlin.time.Duration.Companion.seconds

        suspend fun models(): List<Any?> {
            val info = ScriptedModel.modelInfo("m")
            val scripted = ScriptedModel(EndpointId("main"), listOf(info), TurnContextMode.TRANSIENT)
            scripted.reply(RequestMatch.round(0)) {
                reasoning("Hm.", summary = "short", seal = "sig", sealKind = SealKind.SIGNATURE)
                text("Sav", "ing.")
                toolCall("notes.add", "{}", ToolCallId("c1"))
                opaque("citation", JsonObject(emptyMap()))
                usage(Usage.builder().build())
                finish(FinishKind.TOOL_USE)
                warning("option", "ignored")
                pause { }
            }
            val builder: ScriptedReply.Builder = ScriptedReply.builder().text("Hi")
            val reply: ScriptedReply = builder.fail(ModelErrorKind.TIMEOUT).build()
            scripted.reply(reply, RequestMatch.turn(TurnId("t")))
            scripted.reply(scriptedReply { hang() }, RequestMatch("any") { true })
            scripted.fail(ModelError.builder(ModelErrorKind.RATE_LIMITED, "Slow.").build(), RequestMatch.ANY)
            scripted.fail(ModelErrorKind.OVERLOADED, "Busy.")
            scriptedReply { fail(ModelError.builder(ModelErrorKind.SERVER_ERROR, "Gone.").build()) }
            val request = testModelRequest(testTurn(), 0, listOf(testUserEntry(testTurn(), "hi", testRecord(1)))) {
                model(scripted.ref)
            }
            val events: List<ModelEvent> = scripted.stream(request).toList()
            scripted.assertFinished()
            return listOf(
                events, scripted.requests, scripted.remaining, scripted.problems, scripted.awaitRequests(1),
                scripted.models(), scripted.turnContextMode("m", ModelOptions.DEFAULT, Trust.TRUSTED), scripted.id,
                ScriptedModel.DIALECT,
                TEST_MODEL,
            )
        }

        suspend fun channels(submitter: RecordingTurnSubmitter): List<Any?> {
            val channel = RecordingChannel(TEST_INSTANCE, setOf("ada"), RecordingChannel.DEFAULT_PART_LENGTH, submitter)
            channel.scriptCapabilities(RecordingChannel.DEFAULT_CAPABILITIES, channel.chat("c", "t"))
            channel.scriptDeliveries(Delivery.NotDelivered(DeliveryFailure.CHAT_GONE), chat = null)
            val message: IncomingMessage = channel.message("hi", channel.chat(), channel.user("ada")) { text("hey") }
            val command: CommandInvocation = channel.command("new", "now", channel.chat(), channel.user())
            val admissions = listOf(
                channel.receive("hi"),
                channel.receiveCommand("new"),
                channel.submit(Submission.Message(message)),
            )
            val sink = channel.openReply(ReplyRequest(testTurn(), null))
            sink.preview(0, "Hel")
            sink.complete(OutboundMessage.builder("Hello", MessageKind.REPLY).build())
            channel.send(channel.chat(), OutboundMessage.builder("Note", MessageKind.NOTICE).build())
            val recorded: RecordedReply = channel.awaitReply(TurnId("test-turn")).awaitEnd()
            val preview: RecordedReply.Preview = recorded.previews.first()
            val event = channel.awaitEvent { it is RecordingChannel.Event.Completed }
            val described = when (event) {
                is RecordingChannel.Event.Opened -> event.request
                is RecordingChannel.Event.Previewed -> listOf(event.turn, event.segment, event.text)
                is RecordingChannel.Event.Completed -> listOf(event.turn, event.message, event.delivery)
                is RecordingChannel.Event.Abandoned -> listOf(event.turn, event.end)
                is RecordingChannel.Event.Sent -> listOf(event.chat, event.message, event.delivery)
                is RecordingChannel.Event.Received -> event.submission
            }
            return listOf(
                command, admissions, described, preview.segment, preview.text, recorded.request, recorded.turn,
                recorded.segments, recorded.completed, recorded.delivery, recorded.abandoned, recorded.ended,
                channel.instance, channel.events, channel.replies, channel.reply(TurnId("test-turn")), channel.sent,
                channel.violations, channel.capabilities(channel.chat()),
                channel.partsNeeded(channel.chat(), "x", Markup.PLAIN),
            )
        }

        suspend fun turns(): List<Any?> {
            val submitter = RecordingTurnSubmitter(TurnOutcome.Cancelled)
            submitter.refuseNext(RefusalReason.QUEUE_FULL, 20)
            submitter.submit(Submission.Command(testCommand("new", "", testChat(), testUser(), null)))
            val initiator = RecordingTurnInitiator("probe", null)
            initiator.refuseNext(RefusalReason.NO_AGENT)
            val initiated = InitiatedTurn.builder(testChat("c", null, TEST_INSTANCE), TurnKind.REMINDER, "Go.").build()
            initiator.initiate(initiated)
            val first: RecordingTurnInitiator.Initiated = initiator.awaitInitiated(1).first()
            val ticket = TestTurnTicket(TurnId("t"))
            ticket.complete(TurnOutcome.Completed(null))
            ticket.cancel()
            val context = TestCommandContext(testTurn(TurnKind.COMMAND), null)
            context.scriptDeliveries(Delivery.Delivered(emptyList()))
            context.reply("Done.")
            val invoked = testCommandContext(testCommand("new"))
            val control = RecordingAgentControl(AgentId.MAIN)
            control.statuses = emptyList()
            control.cancel(testChat(), null)
            control.cancelTurn(TurnId("t"), null)
            control.cancelRun(RunId("r"), true, null)
            control.newConversation(testChat(), null)
            control.updateSettings(testChat(), ChatSettingsUpdate.builder().build(), null)
            val call = when (val recorded = control.calls.first()) {
                is RecordingAgentControl.Call.Cancel -> listOf(recorded.chat, recorded.by)
                is RecordingAgentControl.Call.CancelTurn -> listOf(recorded.turn, recorded.by)
                is RecordingAgentControl.Call.CancelRun -> listOf(recorded.run, recorded.tree, recorded.by)
                is RecordingAgentControl.Call.NewConversation ->
                    listOf(recorded.chat, recorded.by, recorded.conversation)
                is RecordingAgentControl.Call.UpdateSettings -> listOf(recorded.chat, recorded.update, recorded.by)
            }
            val states = TestChatStates()
            return listOf(
                submitter.submissions, submitter.results, submitter.tickets, submitter.awaitSubmissions(1),
                initiator.initiated, initiator.results, initiator.tickets, first.plugin, first.turn, ticket.turn,
                ticket.cancelled, ticket.ended, ticket.outcome(), context.turn, context.replies, invoked, call,
                control.turns(null), control.settings(testChat()), states.of("probe"), testChatStates("probe"),
                testToolContext(testDelegatedTurn(testTurn(), RunId("r"), ToolCallId("c")) { }, ToolCallId("c")),
                testMessage("hi", testChat(), testUser("ada", true, TEST_INSTANCE), "m1") { }, TEST_TIME,
            )
        }

        fun payloads(): List<Any> = listOf(
            testPromptSections(testTurn(), emptyList()),
            testTurnStart(
                testTurn(),
                listOf(testToolDefinition("notes.add", ToolRisk.READ_ONLY)),
                testMessageOf(testTurn(), "hi"),
            ),
            testTurnInput(testTurn(), "hi", false, null),
            testContextLoaded(testTurn(), emptyList()),
            testTurnContext(testTurn(), "hi", emptyList()),
            testModelCall(testTurn(), 0, testModelRequest()),
            testModelReply(testTurn(), 0, scriptedReply { text("Hi") }),
            testModelReply(testTurn(), 0, testModelReply().response),
            testToolCallCheck(testTurn(), testToolDefinition(), "{}"),
            testToolCallDone(testTurn(), output = "done", outcome = ToolOutcome.Succeeded),
            testReplyPreview(testTurn(), 0, "Hel"),
            testReplyDraft(testTurn(), "Hi", MessageKind.REPLY),
            testTurnCommitted(testTurn(), usage = null),
            testConversationSealed(testTurn(), ConversationId("next")),
        )

        suspend fun harness(index: PluginIndex, other: PluginIndex, root: Path): List<Any> {
            val model = ScriptedModel()
            val builder: PluginHarness.Builder = PluginHarness.builder(index)
            val harness = builder
                .plugin(other)
                .config("{}")
                .config(JsonObject(emptyMap()))
                .config(other, "{}")
                .config(other, JsonObject(emptyMap()))
                .dataRoot(root)
                .zone(ZoneOffset.UTC)
                .shutdownGrace(1.seconds)
                .inspectTermination()
                .model(model)
                .channel("main", "test", setOf("ada"), 100)
                .turnSubmitter(RecordingTurnSubmitter())
                .turnInitiator(RecordingTurnInitiator())
                .agentControl(RecordingAgentControl())
                .chatStates(TestChatStates())
                .build()
            val seen = mutableListOf<Any>()
            val block: suspend PluginHarness.Running.() -> Unit = {
                seen.addAll(listOf(dataDir, cacheDir, get<ChannelDirectory>(), getAll<ModelProvider>()))
                seen.add(model("scripted"))
                seen.addAll(listOf(channel("main", "test"), channel(TEST_INSTANCE), get(key<ChannelDirectory>())))
                seen.addAll(getAll(key<ModelProvider>()))
                stop()
            }
            return seen + harness.run(block)
        }
    """.trimIndent()

    private fun errors(vararg sources: Pair<String, String>): List<String> = KotlinCompilation().apply {
        workingDir = this@PublicApiTest.workingDir
        this.sources = sources.map { (name, text) -> SourceFile.kotlin(name, text) }
        inheritClassPath = true
        jvmTarget = "21"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile().messages.lines().filter { it.startsWith("e: ") }

    @Test
    fun `a plugin's test uses every double without the internal API`() {
        assertEquals(emptyList(), errors("Sample.kt" to sample))
    }

    @Test
    fun `a test without the internal API cannot call what leaks an internal type in its signature`() {
        val leaking = """
            @file:OptIn(org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi::class)

            package leak

            fun store(): org.foedusprogramme.alexandrite.sdk.chat.ChatStateStore? = null

            fun take(stores: List<org.foedusprogramme.alexandrite.sdk.chat.ChatStateStore>): Int = stores.size
        """.trimIndent()
        val caller = "fun one() = leak.store()\n\nfun two() = leak.take(listOf())"

        val errors = errors("Leak.kt" to leaking, "Use.kt" to caller)

        val lines = errors.map { Regex("Use\\.kt:(\\d+):").find(it)?.groupValues?.get(1) }.toSet()
        assertEquals(setOf("1", "3"), lines, errors.joinToString("\n"))
        assertTrue(errors.all { "Internal Alexandrite API" in it }, errors.joinToString("\n"))
    }

    @Test
    fun `the sample names every public declaration of the testkit`() {
        val missing = publicNames().filterNot { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(sample) }

        assertEquals(emptyList(), missing)
    }

    /** The names of the public classes, functions and properties in the testkit's root package. */
    private fun publicNames(): Set<String> {
        val root = PluginHarness::class.java.packageName
        val classes = Path.of(PluginHarness::class.java.protectionDomain.codeSource.location.toURI())
            .resolve(root.replace('.', '/'))
        val names = sortedSetOf<String>()
        Files.list(classes).use { files ->
            for (file in files.filter { it.extension == "class" }) {
                val type = Class.forName("$root.${file.nameWithoutExtension}")
                val nested = file.nameWithoutExtension.split('$').drop(1)
                if (generateSequence(type) { it.enclosingClass }.any { !Modifier.isPublic(it.modifiers) }) continue
                if (nested.any { !it.first().isUpperCase() || it == "DefaultImpls" }) continue
                if (!type.simpleName.endsWith("Kt") && type.simpleName != "Companion") names += type.simpleName
                for (method in type.declaredMethods) {
                    if (Modifier.isPublic(method.modifiers) && !method.isSynthetic && '$' !in method.name) {
                        names += declared(method)
                    }
                }
                for (field in type.declaredFields) {
                    if (Modifier.isPublic(field.modifiers) && field.name != "Companion") names += field.name
                }
            }
        }
        return names.filterNot { '$' in it || it in KOTLIN_MEMBERS || COMPONENT.matches(it) }.toSet()
    }

    /** The property that [method] reads or writes, or the function it is, without the mangling of value classes. */
    private fun declared(method: Method): String {
        val name = method.name.substringBefore('-')
        val accessor = when (method.parameterCount) {
            0 -> GETTER
            1 -> SETTER.takeIf { method.returnType == Void.TYPE }
            else -> null
        }
        val property = accessor?.matchEntire(name)?.groupValues?.get(1) ?: return name
        val acronym = property.length > 1 && property[1].isUpperCase()
        return if (acronym) property else property.replaceFirstChar(Char::lowercaseChar)
    }

    private companion object {
        val GETTER = Regex("(?:get|is)([A-Z].*)")
        val SETTER = Regex("set([A-Z].*)")
        val COMPONENT = Regex("component\\d+")
        val KOTLIN_MEMBERS = setOf("copy", "equals", "hashCode", "toString")
    }
}
