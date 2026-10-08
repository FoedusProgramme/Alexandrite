package org.foedusprogramme.alexandrite.sdk.di.container

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle

/** How a lifecycle step went for one managed instance. */
@InternalAlexandriteApi
@Poko
public class StepReport(
    /** Id of the plugin that binds the instance. */
    public val plugin: String,
    /** [Binding.origin] of the binding that created the instance. */
    public val origin: String,
    public val step: Step,
    public val outcome: Outcome,
    /** The label of the channel instance container that holds the instance, null for the root container. */
    public val container: String? = null,
) {
    /** The [Lifecycle] callback a report is about. */
    public enum class Step {
        CLOSE,
        DRAIN,
        STOP,

        /** [Lifecycle.onDestroy], then [AutoCloseable.close]. */
        DESTROY,
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
