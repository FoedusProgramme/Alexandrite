package org.foedusprogramme.alexandrite.sdk

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.di.container.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ValueTypesTest {
    private val error = IllegalStateException("failed")

    private class Case(val make: () -> Any, val differing: Any, val printed: String)

    private val cases = listOf(
        Case(
            { Dependency(key<String>("a"), DependencyKind.INSTANCE, "a") },
            Dependency(key<String>("a"), DependencyKind.OPTIONAL, "a"),
            "Dependency(key=@Named(\"a\") kotlin.String, kind=INSTANCE, site=a)",
        ),
        Case(
            { PluginBindings("weather", emptyList()) },
            PluginBindings("rain", emptyList()),
            "PluginBindings(id=weather, bindings=[])",
        ),
        Case(
            { Problem(DiProblemKind.MISSING, "gone", "weather") },
            Problem(DiProblemKind.MISSING, "gone", "rain"),
            "Problem(kind=MISSING, message=gone, plugin=weather)",
        ),
        Case(
            { PluginInfo("weather", "Weather", "1.0", "Forecasts", 1, listOf("geo"), "sample.Weather") },
            PluginInfo("weather", "Weather", "1.1", "Forecasts", 1, listOf("geo"), "sample.Weather"),
            "PluginInfo(id=weather, name=Weather, version=1.0, description=Forecasts, sdkApi=1, requires=[geo], " +
                "entryClass=sample.Weather)",
        ),
        Case(
            { ToolDefinition("fs.read", "Reads a file", JsonObject(emptyMap())) },
            ToolDefinition("fs.read", "Reads files", JsonObject(emptyMap())),
            "ToolDefinition(name=fs.read, description=Reads a file, parameters={}, risk=EXEC)",
        ),
        Case(
            { ToolResult("ok") },
            ToolResult("ok", isError = true),
            "ToolResult(content=[TextPart(text=ok)], isError=false)",
        ),
        Case({ HookDecision.Replace("x") }, HookDecision.Replace("y"), "Replace(payload=x)"),
        Case({ HookDecision.Abort("no") }, HookDecision.Abort(), "Abort(reply=no)"),
        Case({ Interception.Proceed("x") }, Interception.Proceed("y"), "Proceed(payload=x)"),
        Case(
            { Interception.Aborted(null, "sample.Guard", HookFailure.TimedOut(1.seconds)) },
            Interception.Aborted("no", "sample.Guard", null),
            "Aborted(reply=null, hook=sample.Guard, failure=TimedOut(timeout=1s))",
        ),
        Case({ HookFailure.Threw(error) }, HookFailure.Threw(IllegalStateException("failed")), "Threw(error=$error)"),
        Case({ HookFailure.TimedOut(1.seconds) }, HookFailure.TimedOut(2.seconds), "TimedOut(timeout=1s)"),
        Case(
            { HookFailure.Disallowed(HookDecision.Abort()) },
            HookFailure.Disallowed(HookDecision.Continue),
            "Disallowed(decision=Abort(reply=null))",
        ),
        Case(
            { StopRequest(StopKind.SHUTDOWN, "signal") },
            StopRequest(StopKind.RESTART, "signal"),
            "StopRequest(kind=SHUTDOWN, reason=signal, plugin=null)",
        ),
        Case(
            { StopRequest.failure("disk full").from("store") },
            StopRequest.failure("disk full"),
            "StopRequest(kind=FAILURE, reason=disk full, plugin=store)",
        ),
        Case(
            { StepReport("weather", "Radar", StepReport.Step.DRAIN, StepReport.Outcome.TimedOut) },
            StepReport("weather", "Radar", StepReport.Step.CLOSE, StepReport.Outcome.TimedOut),
            "StepReport(plugin=weather, origin=Radar, step=DRAIN, outcome=TimedOut, container=null)",
        ),
        Case(
            {
                StepReport("tg", "Bot", StepReport.Step.STOP, StepReport.Outcome.Completed, "channel instance 'tg'")
            },
            StepReport("tg", "Bot", StepReport.Step.STOP, StepReport.Outcome.Completed),
            "StepReport(plugin=tg, origin=Bot, step=STOP, outcome=Completed, container=channel instance 'tg')",
        ),
        Case(
            { StepReport.Outcome.Failed(error) },
            StepReport.Outcome.Failed(IllegalStateException("failed")),
            "Failed(error=$error)",
        ),
    )

    @Test
    fun `values are equal by their properties`() {
        for (case in cases) {
            assertEquals(case.make(), case.make())
            assertEquals(case.make().hashCode(), case.make().hashCode(), case.printed)
            assertNotEquals(case.make(), case.differing)
        }
    }

    @Test
    fun `values print their properties`() {
        for (case in cases) assertEquals(case.printed, case.make().toString())
    }

    @Test
    fun `stop requests are built by kind`() {
        assertEquals(
            listOf(StopKind.SHUTDOWN, StopKind.RESTART, StopKind.FAILURE).map { StopRequest(it, "why") },
            listOf(StopRequest.shutdown("why"), StopRequest.restart("why"), StopRequest.failure("why")),
        )
    }

    @Test
    fun `values have no copy or component functions`() {
        for (case in cases) {
            val type = case.make().javaClass
            assertTrue(type.methods.none { it.name == "copy" || it.name.startsWith("component") }, type.name)
        }
    }
}
