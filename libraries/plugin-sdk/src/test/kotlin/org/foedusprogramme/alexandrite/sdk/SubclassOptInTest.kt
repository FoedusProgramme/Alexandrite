@file:OptIn(ExperimentalCompilerApi::class)

package org.foedusprogramme.alexandrite.sdk

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubclassOptInTest {
    @TempDir
    lateinit var workingDir: File

    private val implementations = """
        class Files : org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles {
            override val dataDir get() = TODO()
            override val cacheDir get() = TODO()
        }

        abstract class Index : org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex

        class Control : org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl {
            override fun stop(request: org.foedusprogramme.alexandrite.sdk.runtime.StopRequest) = TODO()
        }

        class Firing : org.foedusprogramme.alexandrite.sdk.hook.Hooks {
            override suspend fun <P : Any> fire(
                point: org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint<P>,
                payload: P,
            ): org.foedusprogramme.alexandrite.sdk.hook.Interception<P> = TODO()

            override suspend fun <P : Any> fire(
                point: org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint<P>,
                payload: P,
            ): Unit = TODO()
        }

        class Context : org.foedusprogramme.alexandrite.sdk.tool.ToolContext {
            override val turn get() = TODO()
            override val call get() = TODO()
        }

        class States : org.foedusprogramme.alexandrite.sdk.chat.ChatStates {
            override fun <T : Any> state(
                name: String,
                serializer: kotlinx.serialization.KSerializer<T>,
                default: T,
            ): org.foedusprogramme.alexandrite.sdk.chat.ChatState<T> = TODO()
        }

        class Settings : org.foedusprogramme.alexandrite.sdk.chat.ChatSettings {
            override suspend fun language(chat: org.foedusprogramme.alexandrite.sdk.chat.ChatAddress) = TODO()
        }

        class Ended : org.foedusprogramme.alexandrite.sdk.hook.Interception<Nothing>

        class Failure : org.foedusprogramme.alexandrite.sdk.hook.HookFailure

        class Entry : org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry {
            override val record get() = TODO()
        }

        class Origin : org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin

        class Piece : org.foedusprogramme.alexandrite.sdk.transcript.Part

        class Input : org.foedusprogramme.alexandrite.sdk.transcript.UserPart

        class Output : org.foedusprogramme.alexandrite.sdk.transcript.ToolOutputPart

        class Reply : org.foedusprogramme.alexandrite.sdk.transcript.AssistantPart

        class Source : org.foedusprogramme.alexandrite.sdk.transcript.MediaSource

        class Outcome : org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome {
            override val isError get() = TODO()
        }

        class Event : org.foedusprogramme.alexandrite.sdk.model.ModelEvent

        class Choice : org.foedusprogramme.alexandrite.sdk.model.ToolChoice
    """.trimIndent()

    /** `when`s over open hierarchies and enumerations, each with an `ELSE` line. */
    private val consumers = """
        import org.foedusprogramme.alexandrite.sdk.model.*

        fun event(event: ModelEvent): Int = when (event) {
            is ModelEvent.ResponseStarted -> 0
            is ModelEvent.TextDelta -> 1
            is ModelEvent.ReasoningDelta -> 2
            is ModelEvent.ReasoningSealed -> 3
            is ModelEvent.ToolCallStarted -> 4
            is ModelEvent.ToolArgumentsDelta -> 5
            is ModelEvent.PartCompleted -> 6
            is ModelEvent.UsageUpdated -> 7
            is ModelEvent.Completed -> 8
            ELSE
        }

        fun choice(choice: ToolChoice): Int = when (choice) {
            ToolChoice.Auto -> 0
            ToolChoice.None -> 1
            ToolChoice.Required -> 2
            is ToolChoice.Named -> 3
            ELSE
        }

        fun effort(effort: ReasoningEffort): Int = when (effort) {
            ReasoningEffort.NONE -> 0
            ReasoningEffort.MINIMAL -> 1
            ReasoningEffort.LOW -> 2
            ReasoningEffort.MEDIUM -> 3
            ReasoningEffort.HIGH -> 4
            ReasoningEffort.XHIGH -> 5
            ReasoningEffort.MAX -> 6
            ELSE
        }

        fun finish(kind: FinishKind): Int = when (kind) {
            FinishKind.END_TURN -> 0
            FinishKind.TOOL_USE -> 1
            FinishKind.STOP_SEQUENCE -> 2
            FinishKind.MAX_OUTPUT_TOKENS -> 3
            FinishKind.CONTEXT_WINDOW_EXCEEDED -> 4
            FinishKind.PAUSED -> 5
            FinishKind.REFUSAL -> 6
            FinishKind.OTHER -> 7
            ELSE
        }

        fun error(kind: ModelErrorKind): Int = when (kind) {
            ModelErrorKind.AUTHENTICATION -> 0
            ModelErrorKind.PERMISSION_DENIED -> 1
            ModelErrorKind.QUOTA_EXHAUSTED -> 2
            ModelErrorKind.RATE_LIMITED -> 3
            ModelErrorKind.OVERLOADED -> 4
            ModelErrorKind.SERVER_ERROR -> 5
            ModelErrorKind.TIMEOUT -> 6
            ModelErrorKind.CONNECTION -> 7
            ModelErrorKind.INVALID_REQUEST -> 8
            ModelErrorKind.CONTEXT_WINDOW_EXCEEDED -> 9
            ModelErrorKind.MODEL_NOT_FOUND -> 10
            ModelErrorKind.CONTENT_FILTERED -> 11
            ModelErrorKind.UNSUPPORTED -> 12
            ModelErrorKind.PROTOCOL -> 13
            ELSE
        }
    """.trimIndent()

    /** A model provider as a third-party plugin writes it. */
    private val provider = """
        import kotlinx.coroutines.flow.Flow
        import kotlinx.coroutines.flow.flow
        import org.foedusprogramme.alexandrite.sdk.model.*
        import org.foedusprogramme.alexandrite.sdk.transcript.*

        class Echo : ModelEndpoint {
            override val id = EndpointId("echo")

            override suspend fun models() =
                listOf(ModelInfo.builder("echo-1", Dialect("echo")).nativeTools(true).streaming(true).build())

            override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
                if (request.tools.isEmpty()) {
                    throw ModelException(ModelError.builder(ModelErrorKind.UNSUPPORTED, "No tools.").build())
                }
                emit(ModelEvent.ResponseStarted(null, request.model.model, emptyList()))
                emit(ModelEvent.TextDelta(0, "Hi"))
                emit(ModelEvent.PartCompleted(0, TextPart("Hi")))
                val call = ToolCallPart(request.ids.callId(1), request.tools.first().name, "{}")
                emit(ModelEvent.ToolCallStarted(1, call.id, call.name))
                emit(ModelEvent.PartCompleted(1, call))
                val usage = Usage.builder().inputTokens(3).outputTokens(2).contextTokens(3).build()
                val message = AssistantEntry(null, listOf(TextPart("Hi"), call), request.model)
                emit(ModelEvent.Completed(message, FinishReason(FinishKind.TOOL_USE, "tool_calls", null), usage))
            }

            override fun turnContextMode(model: String, options: ModelOptions, trust: Trust) =
                if (trust == Trust.TRUSTED) TurnContextMode.TRANSIENT else TurnContextMode.NOT_SUPPORTED
        }

        class EchoProvider : ModelProvider {
            override val endpoints = listOf(Echo())
        }
    """.trimIndent()

    /** The errors of compiling [source]. */
    private fun errors(source: String): List<String> = KotlinCompilation().apply {
        workingDir = this@SubclassOptInTest.workingDir
        sources = listOf(SourceFile.kotlin("Sample.kt", source))
        inheritClassPath = true
        jvmTarget = "21"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile().messages.lines().filter { it.startsWith("e: ") }

    @Test
    fun `only code that opts in to the internal API implements the types Alexandrite implements`() {
        val classes = implementations.lines().filter { it.startsWith("class ") || it.startsWith("abstract class ") }
        val errors = errors(implementations)

        assertEquals(classes.size, errors.size, errors.joinToString("\n"))
        assertTrue(errors.all { "Internal Alexandrite API" in it }, errors.joinToString("\n"))
        val optIn = "@file:OptIn(org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi::class)"
        assertEquals(emptyList(), errors("$optIn\n\n$implementations"))
    }

    @Test
    fun `a when over an open hierarchy or enumeration compiles only with an else branch`() {
        val whens = consumers.lines().count { it.trim() == "ELSE" }
        val errors = errors(consumers.replace("ELSE", ""))

        assertEquals(whens, errors.size, errors.joinToString("\n"))
        assertTrue(errors.all { "exhaustive" in it }, errors.joinToString("\n"))
        assertEquals(emptyList(), errors(consumers.replace("ELSE", "else -> -1")))
    }

    @Test
    fun `a plugin implements a model provider without the internal API`() {
        assertEquals(emptyList(), errors(provider))
    }
}
