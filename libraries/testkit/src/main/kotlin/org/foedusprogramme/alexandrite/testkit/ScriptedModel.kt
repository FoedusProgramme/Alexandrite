package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptRules

/**
 * A model endpoint that answers each request with the first queued step that matches it, and records the requests.
 *
 * A request that no step matches, that names another endpoint or whose history breaks [TranscriptRules] throws an
 * [AssertionError] from its stream and fails the [PluginHarness] run that holds the model.
 */
public class ScriptedModel(
    override val id: EndpointId = EndpointId("scripted"),
    models: List<ModelInfo> = listOf(modelInfo()),
    private val turnContextMode: TurnContextMode = TurnContextMode.TRANSIENT,
) : ModelEndpoint {
    private val models = models.toList()
    private val lock = Any()
    private val steps = mutableListOf<Step>()
    private val received = MutableStateFlow<List<ModelRequest>>(emptyList())
    private val problemList = mutableListOf<String>()

    init {
        require(this.models.isNotEmpty()) { "A scripted model serves at least one model." }
        val repeated = this.models.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(repeated.isEmpty()) { "Scripted model '$id' serves ${repeated.joinToString()} more than once." }
    }

    /** The endpoint's first model. */
    public val ref: ModelRef get() = ModelRef(id, models.first().id)

    /** The requests whose streams were collected, in order. */
    public val requests: List<ModelRequest> get() = received.value

    /** How many queued steps no request took yet. */
    public val remaining: Int get() = synchronized(lock) { steps.size }

    public val problems: List<String> get() = synchronized(lock) { problemList.toList() }

    /** Queues a reply built by [block] for the first request that [match] accepts. */
    public fun reply(match: RequestMatch = RequestMatch.ANY, block: ScriptedReply.Builder.() -> Unit): ScriptedModel =
        reply(scriptedReply(block), match)

    public fun reply(reply: ScriptedReply, match: RequestMatch = RequestMatch.ANY): ScriptedModel =
        queue(Step(match, reply, null))

    /** Queues a [ModelException] of [error], thrown before any event, for the first request that [match] accepts. */
    public fun fail(error: ModelError, match: RequestMatch = RequestMatch.ANY): ScriptedModel =
        queue(Step(match, null, error))

    public fun fail(
        kind: ModelErrorKind,
        message: String = "Scripted $kind failure.",
        match: RequestMatch = RequestMatch.ANY,
    ): ScriptedModel = fail(ModelError.builder(kind, message).build(), match)

    /** Suspends until [count] requests were received, and returns them. */
    public suspend fun awaitRequests(count: Int): List<ModelRequest> = received.first { it.size >= count }.take(count)

    /** Throws an [AssertionError] when the model met a problem or a queued step was not taken. */
    public fun assertFinished() {
        val (problems, left) = synchronized(lock) { problemList.toList() to steps.map { it.match } }
        val messages = problems + listOfNotNull(
            "Scripted model '$id' has ${left.size} step(s) no request took: ${left.joinToString()}."
                .takeIf { left.isNotEmpty() },
        )
        if (messages.isNotEmpty()) throw AssertionError(messages.joinToString("\n"))
    }

    override suspend fun models(): List<ModelInfo> = models

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode = turnContextMode

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val (number, step) = take(request)
        step.error?.let { throw ModelException(it) }
        val info = models.first { it.id == request.model.model }
        with(step.reply!!) { respond("$id-$number", request.model, request.ids, info.dialect) }
    }

    private fun queue(step: Step): ScriptedModel = apply { synchronized(lock) { steps += step } }

    /** Records [request] and takes the step that answers it. */
    private fun take(request: ModelRequest): Pair<Int, Step> {
        val failure: String
        synchronized(lock) {
            val all = received.value + request
            received.value = all
            val name = "Scripted model '$id' got request ${all.size} (${describe(request)})"
            val broken = TranscriptRules.check(request.history)
            val index = steps.indexOfFirst { it.match.matches(request) }
            failure = when {
                request.model.endpoint != id -> "$name for endpoint '${request.model.endpoint}'."

                broken.isNotEmpty() -> "$name, whose history breaks the transcript rules: ${broken.joinToString(" ")}"

                models.none { it.id == request.model.model } -> throw ModelException(
                    ModelError.builder(ModelErrorKind.MODEL_NOT_FOUND, "No model '${request.model.model}' at '$id'.")
                        .build(),
                )

                steps.isEmpty() -> "$name, but its script has no step left."

                index < 0 ->
                    "$name, but none of its ${steps.size} step(s) matches it: " +
                        "${steps.joinToString { it.match.toString() }}."

                else -> return all.size to steps.removeAt(index)
            }
            problemList += failure
        }
        throw AssertionError(failure)
    }

    private class Step(val match: RequestMatch, val reply: ScriptedReply?, val error: ModelError?)

    public companion object {
        /** The dialect of the default model and of its reasoning seals. */
        public val DIALECT: Dialect = Dialect("scripted")

        /** A model that takes tools, calls them in parallel, streams and reasons. */
        public fun modelInfo(id: String = "test-model"): ModelInfo = ModelInfo.builder(id, DIALECT)
            .nativeTools(true)
            .parallelToolCalls(true)
            .streaming(true)
            .reasoningEfforts(setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH))
            .build()
    }
}

/** Which requests a step of a [ScriptedModel] answers. */
public class RequestMatch(private val description: String, private val predicate: (ModelRequest) -> Boolean) {
    internal fun matches(request: ModelRequest): Boolean = predicate(request)

    override fun toString(): String = description

    public companion object {
        public val ANY: RequestMatch = RequestMatch("any request") { true }

        public fun round(round: Int): RequestMatch = RequestMatch("round $round") { it.ids.round == round }

        public fun turn(turn: TurnId): RequestMatch = RequestMatch("turn $turn") { it.ids.turn == turn }
    }
}

private fun describe(request: ModelRequest): String =
    "round ${request.ids.round} of turn ${request.ids.turn}, model ${request.model}"
