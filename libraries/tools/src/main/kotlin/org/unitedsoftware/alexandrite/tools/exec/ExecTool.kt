package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.internal.path.homeDirectory
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.tools.fs.property
import org.unitedsoftware.alexandrite.tools.fs.schema
import org.unitedsoftware.alexandrite.tools.monitor.MonitorOptions
import org.unitedsoftware.alexandrite.tools.monitor.monitoring
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Singleton
@Contribute(Tool::class)
internal class ExecTool(private val floor: HardFloor, private val config: ExecConfig) : ReviewedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "command.exec",
        description = "Runs one program with its arguments, without a shell, and returns its exit code and its " +
            "output (stdout and stderr together; of a long output, the start and the end). Give either 'command', " +
            "one line split like a shell splits it (quotes and backslashes; a leading ~ is the home directory) but " +
            "with no pipes, redirects, chaining, variables, globs or substitutions, or 'argv'. For shell features " +
            "run sh -c '…' explicitly. The program gets no input and a reduced environment, runs in 'cwd' and is " +
            "stopped with every process it started after 'timeoutSeconds'. Refuses calls that reach protected " +
            "places. Set background to true to start immediately as a job. A monitor starts a background job " +
            "and wakes you with filtered output batches until the program exits or its lifetime expires. " +
            "Monitor buffers are bounded and excess output can be dropped with a count.",
        parameters = schema {
            property("background", "boolean", "Start immediately as a background job (default false).")
            putJsonObject("monitor") {
                put("type", "object")
                put("additionalProperties", false)
                put("required", JsonArray(listOf(JsonPrimitive("filter"))))
                putJsonObject("properties") {
                    property(
                        "filter",
                        "string",
                        "Literal substring selecting output lines, nonempty and at most 1024 characters.",
                    )
                    property("batchSeconds", "integer", "Batch window, 1..30 seconds (default 3).")
                    property("intervalSeconds", "integer", "Minimum wake interval, 1..3600 seconds (default 10).")
                    property(
                        "maxLifetimeSeconds",
                        "integer",
                        "Maximum monitor lifetime, no longer than timeoutSeconds.",
                    )
                }
            }
            property("command", "string", "The program and its arguments as one line, such as \"./gradlew test\".")
            putJsonObject("argv") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "The program and its arguments, each passed as it is. Instead of 'command'.")
            }
            property(
                "cwd",
                "string",
                "The directory to run in: an absolute path, or one relative to your workspace (~ is home). " +
                    "Default your workspace.",
            )
            property(
                "timeoutSeconds",
                "integer",
                "How long the program may run, in seconds: at most ${config.maxTimeoutSeconds}, which is the default.",
            )
        },
        risk = ToolRisk.EXEC,
        timeout = config.maxTimeoutSeconds.seconds,
        activity = ToolActivity.EXECUTE,
        titleArguments = listOf("command", "argv"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
        when (val inspection = inspect(arguments, context, running = false)) {
            is Inspection.Problem -> CallReview(problem = inspection.text)

            is Inspection.Refused -> CallReview(refusal = inspection.text)

            is Inspection.Ready -> with(inspection.run) {
                CallReview(
                    risk = if (readOnly) ToolRisk.READ_ONLY else ToolRisk.EXEC,
                    preview = ReviewPreview.Command(argv, cwd, executable, opaque),
                    snapshot = snapshot,
                    rule = rule,
                    background = startsInBackground(arguments),
                )
            }
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val ready = when (val inspection = inspect(arguments, context, running = true)) {
            is Inspection.Problem -> return ToolResult(inspection.text, isError = true)
            is Inspection.Refused -> return ToolResult(inspection.text, isError = true)
            is Inspection.Ready -> inspection
        }
        val run = ready.run
        val monitor = ready.monitor
        val clearance = context.clearance
        val reviewed = clearance?.snapshot
        if (reviewed != null && reviewed != run.snapshot) {
            return ToolResult(
                "${run.name} or a file it is given changed since the call was reviewed, so it is not run: call " +
                    "again to have it reviewed anew.",
                isError = true,
            )
        }
        if (!run.vetted && (clearance == null || clearance.kind == ClearanceKind.NONE)) {
            return ToolResult(
                "${run.name} is no vetted read-only program, so it runs only once the permission layer allowed " +
                    "the call, which it did not.",
                isError = true,
            )
        }
        context.touched(run.cwd)
        run.reached.forEach(context::touched)
        if (!run.vetted) context.untrusted("command.exec ran ${run.program}")
        var finalMonitorOutput = "[monitor ended]"
        val outcome = try {
            suspend fun runWithLines(onLine: ((String) -> Unit)?): ProgramOutcome = runProgram(
                listOf(run.resolved.toString()) + run.argv.drop(1),
                run.cwd,
                childEnvironment(System.getenv(), config.environment),
                monitor?.lifetime ?: run.timeout,
                config.maxOutputChars,
                onLine,
            )
            if (monitor == null) {
                runWithLines(null)
            } else {
                val monitored = monitoring(monitor, context, ::runWithLines)
                finalMonitorOutput = buildString {
                    append("[monitor ended]")
                    if (monitored.remaining.isNotEmpty()) {
                        append("\nMonitor remaining matched output:\n")
                        append(monitored.remaining.joinToString("\n"))
                    }
                    val dropped = monitored.dropped
                    if (dropped > 0) append("\n[$dropped matching lines dropped by buffer limits]")
                }
                monitored.value
            }
        } catch (e: IOException) {
            return ToolResult(
                "${run.name} could not be started: ${e.message ?: e.javaClass.simpleName}",
                isError = true,
            )
        }
        val output = if (monitor == null) {
            outcome.output.trimEnd().ifEmpty { "[no output]" }
        } else {
            finalMonitorOutput
        }
        val exitCode = outcome.exitCode ?: return if (monitor != null) {
            ToolResult(
                "The monitor ended at its lifetime of ${monitor.lifetime.inWholeSeconds} s and stopped the program " +
                    "with the processes it started.\n$output",
            )
        } else {
            ToolResult(
                "The program timed out after ${run.timeout.inWholeSeconds} s and was stopped with the processes it " +
                    "started. Its output until then:\n$output",
                isError = true,
            )
        }
        return ToolResult("Exit code: $exitCode\n$output", isError = exitCode != 0)
    }

    /** What the call would run, or why it cannot run, with the workspace created only when it is [running]. */
    private suspend fun inspect(arguments: JsonObject, context: ToolContext, running: Boolean): Inspection = try {
        val parsed = request(arguments)
        val monitor = monitorOptions(arguments, parsed.timeout)
        val cwd = parsed.cwd?.let { resolveCwd(it, context, running) } ?: run {
            val workspace = context.workspace
                ?: throw CallProblem("Give 'cwd': you have no workspace to run in by default.")
            if (running) context.openWorkspace() else workspace
        }
        val planner = Planner(floor, context.turn.agent, context.turn.rootConversation, isOperator(context))
        val missing = parsed.cwd == null && !running
        val run = Request(parsed.argv, cwd, parsed.timeout)
        Inspection.Ready(runInterruptible(Dispatchers.IO) { planner.plan(run, cwd, missingAllowed = missing) }, monitor)
    } catch (e: CallProblem) {
        Inspection.Problem(e.message)
    } catch (e: FloorRefusal) {
        Inspection.Refused(e.message)
    }

    /** [text] as an absolute directory: a relative one resolved against the workspace, as the default cwd is. */
    private suspend fun resolveCwd(text: String, context: ToolContext, running: Boolean): Path {
        val path = try {
            Path.of(expandHome(text))
        } catch (e: InvalidPathException) {
            throw CallProblem("'cwd' is no valid path.")
        }
        if (path.isAbsolute) return path.normalize()
        val workspace = context.workspace ?: throw CallProblem(
            "'cwd' is a relative path, and you have no workspace to resolve it against: give an absolute path.",
        )
        return (if (running) context.openWorkspace() else workspace).resolve(path).normalize()
    }

    private fun request(arguments: JsonObject): ParsedRequest {
        arguments.optional("background")?.let { value ->
            if ((value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null) {
                throw CallProblem("'background' must be a boolean.")
            }
        }
        val command = arguments.optional("command")?.let { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw CallProblem("'command' must be a string.")
        }
        val argv = arguments.optional("argv")?.let { value ->
            val items = value as? JsonArray ?: throw CallProblem("'argv' must be an array of strings.")
            items.map { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw CallProblem("'argv' must be an array of strings.")
            }
        }
        val words = when {
            command != null && argv != null -> throw CallProblem("Give either 'command' or 'argv', not both.")

            command != null -> CommandLine.split(command, homeDirectory())

            // argv has no quoting, so a word's leading '~' always expands.
            argv != null -> argv.map { expandHome(it) }

            else -> throw CallProblem("Give 'command' (a command line) or 'argv' (the program and its arguments).")
        }
        if (words.isEmpty() || words.first().isEmpty()) throw CallProblem("The call names no program.")
        if (words.any { '\u0000' in it }) {
            throw CallProblem("An argument holds a NUL character, which no program takes.")
        }
        val cwd = arguments.optional("cwd")?.let { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw CallProblem("'cwd' must be a string.")
        }
        val seconds = arguments.optional("timeoutSeconds")?.let { value ->
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 1 }
                ?: throw CallProblem("'timeoutSeconds' must be a whole number of at least 1.")
        } ?: config.maxTimeoutSeconds
        if (seconds > config.maxTimeoutSeconds) {
            throw CallProblem("'timeoutSeconds' may be at most ${config.maxTimeoutSeconds}.")
        }
        return ParsedRequest(words, cwd, seconds.seconds)
    }

    /**
     * Whether the call asks to start as a background job, with `background` (checked by [request]) or a `monitor`
     * (checked by [monitorOptions]).
     */
    private fun startsInBackground(arguments: JsonObject): Boolean =
        (arguments["background"] as? JsonPrimitive)?.booleanOrNull == true || arguments["monitor"] is JsonObject

    private fun monitorOptions(arguments: JsonObject, timeout: Duration): MonitorOptions? {
        val value = arguments.optional("monitor") ?: return null
        val monitor = value as? JsonObject ?: throw CallProblem("'monitor' must be an object.")
        if (monitor.keys.any { it !in MONITOR_FIELDS }) throw CallProblem("Unknown monitor option.")
        val filter = (monitor["filter"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() && it.length <= 1024 }
            ?: throw CallProblem("'monitor.filter' must be a nonempty string of at most 1024 characters.")
        fun seconds(name: String, default: Int, maximum: Int): Duration {
            val raw = monitor[name] ?: return default.seconds
            val number = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?.takeIf { it in 1..maximum }
                ?: throw CallProblem("'monitor.$name' must be a whole number from 1 to $maximum.")
            return number.seconds
        }
        return MonitorOptions(
            filter,
            seconds("batchSeconds", 3, 30),
            seconds("intervalSeconds", 10, 3600),
            seconds("maxLifetimeSeconds", timeout.inWholeSeconds.toInt(), timeout.inWholeSeconds.toInt()),
        )
    }

    private fun isOperator(context: ToolContext): Boolean =
        context.turn.actorIsAdmin && context.turn.kind in OPERATOR_KINDS

    private sealed interface Inspection {
        class Problem(val text: String) : Inspection

        class Refused(val text: String) : Inspection

        class Ready(val run: PlannedRun, val monitor: MonitorOptions?) : Inspection
    }

    /** The call's arguments before 'cwd' is resolved against the workspace. */
    private class ParsedRequest(val argv: List<String>, val cwd: String?, val timeout: Duration)
}

private fun JsonObject.optional(name: String) = this[name]?.takeUnless { it == JsonNull }

/** The kinds of turn whose admin actor makes them an operator's. */
private val OPERATOR_KINDS = setOf(TurnKind.MESSAGE, TurnKind.JOB, TurnKind.DELEGATED)

private val MONITOR_FIELDS = setOf("filter", "batchSeconds", "intervalSeconds", "maxLifetimeSeconds")
