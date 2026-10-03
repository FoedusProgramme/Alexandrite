package org.foedusprogramme.alexandrite.runtime

import java.util.Objects

/** Told about each [RuntimeEvent]. */
public fun interface RuntimeListener {
    public fun onEvent(event: RuntimeEvent)
}

/** A step in the life of an [AlexandriteRuntime]. */
public sealed interface RuntimeEvent {
    /** The plugins to load are known and their config is decoded. */
    public class PluginsResolved internal constructor(
        public val loaded: List<LoadedPlugin>,
        public val disabled: List<DisabledPlugin>,
        /** Index classes on the class path that the runtime does not load. */
        public val unlisted: List<String>,
        /** Config paths below `plugins` that no plugin of the plugin set reads. */
        public val unknownPluginConfig: List<String>,
    ) : RuntimeEvent {
        override fun equals(other: Any?): Boolean = other is PluginsResolved &&
            loaded == other.loaded &&
            disabled == other.disabled &&
            unlisted == other.unlisted &&
            unknownPluginConfig == other.unknownPluginConfig

        override fun hashCode(): Int = Objects.hash(loaded, disabled, unlisted, unknownPluginConfig)

        override fun toString(): String = "PluginsResolved(loaded=$loaded, disabled=$disabled, unlisted=$unlisted, " +
            "unknownPluginConfig=$unknownPluginConfig)"
    }

    public data object Started : RuntimeEvent

    public class StartFailed internal constructor(public val error: RuntimeStartException) : RuntimeEvent {
        override fun equals(other: Any?): Boolean = other is StartFailed && error == other.error

        override fun hashCode(): Int = error.hashCode()

        override fun toString(): String = "StartFailed(error=$error)"
    }

    public data object Closed : RuntimeEvent
}

/** A plugin the plugin config leaves off. */
public class DisabledPlugin internal constructor(public val id: String, public val reason: Reason) {
    /** Why a plugin is off. */
    public enum class Reason {
        /** Its config sets `enabled` to false. */
        ENABLED_FALSE,

        /** A built-in channel or provider without a config section. */
        NOT_CONFIGURED,
    }

    override fun equals(other: Any?): Boolean = other is DisabledPlugin && id == other.id && reason == other.reason

    override fun hashCode(): Int = Objects.hash(id, reason)

    override fun toString(): String = "DisabledPlugin(id=$id, reason=$reason)"
}
