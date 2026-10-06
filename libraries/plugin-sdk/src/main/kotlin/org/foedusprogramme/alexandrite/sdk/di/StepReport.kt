package org.foedusprogramme.alexandrite.sdk.di

import dev.drewhamilton.poko.Poko

/** How a lifecycle step went for one managed instance. */
@Poko
public class StepReport(
    /** Id of the plugin that binds the instance. */
    public val plugin: String,
    /** [Binding.origin] of the binding that created the instance. */
    public val origin: String,
    public val step: Step,
    public val outcome: Outcome,
) {
    /** The [Lifecycle] callback a report is about. */
    public enum class Step {
        CLOSE,
        DRAIN,
        STOP,
    }

    /** How the call of one instance ended. */
    public sealed interface Outcome {
        public data object Completed : Outcome

        @Poko
        public class Failed(public val error: Throwable) : Outcome

        /** Cancelled at the deadline. */
        public data object TimedOut : Outcome

        /** Skipped because the deadline had passed. */
        public data object NotCalled : Outcome
    }
}
