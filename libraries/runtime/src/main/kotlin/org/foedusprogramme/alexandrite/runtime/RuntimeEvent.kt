package org.foedusprogramme.alexandrite.runtime

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.runtime.plugin.DisabledPlugin
import org.foedusprogramme.alexandrite.runtime.plugin.LoadedPlugin
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest

/** Told about each [RuntimeEvent]. */
public fun interface RuntimeListener {
    public fun onEvent(event: RuntimeEvent)
}

/** A step in the life of an [AlexandriteRuntime]. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface RuntimeEvent {
    /** Index classes on the class path that the runtime does not load. */
    @Poko
    public class UnlistedIndexes internal constructor(public val classes: List<String>) : RuntimeEvent

    /** The plugins to load are known and their config is decoded. */
    @Poko
    public class PluginsResolved internal constructor(
        public val loaded: List<LoadedPlugin>,
        public val disabled: List<DisabledPlugin>,
        /** Config paths below `plugins` that no plugin of the plugin set reads. */
        public val unknownPluginConfig: List<String>,
    ) : RuntimeEvent

    /** Every instance has started. */
    public data object Started : RuntimeEvent

    /** Every instance is open. */
    public data object Ready : RuntimeEvent

    @Poko
    public class StartFailed internal constructor(public val error: RuntimeStartException) : RuntimeEvent

    @Poko
    public class Stopping internal constructor(public val request: StopRequest) : RuntimeEvent

    @Poko
    public class Stopped internal constructor(public val termination: Termination) : RuntimeEvent
}
