package org.foedusprogramme.alexandrite.runtime

import java.nio.file.Path
import java.time.ZoneId
import java.util.Objects
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Settings of the runtime itself. */
public class RuntimeConfig private constructor(
    public val dataDir: Path,
    public val zone: ZoneId,
    public val shutdownGrace: Duration,
    /** How long [AlexandriteRuntime.start] may take. */
    public val startTimeout: Duration,
    /** Names the instance in messages. */
    public val name: String,
) {
    override fun equals(other: Any?): Boolean = other is RuntimeConfig &&
        dataDir == other.dataDir &&
        zone == other.zone &&
        shutdownGrace == other.shutdownGrace &&
        startTimeout == other.startTimeout &&
        name == other.name

    override fun hashCode(): Int = Objects.hash(dataDir, zone, shutdownGrace, startTimeout, name)

    override fun toString(): String = "RuntimeConfig(dataDir=$dataDir, zone=$zone, shutdownGrace=$shutdownGrace, " +
        "startTimeout=$startTimeout, name=$name)"

    public class Builder internal constructor(private val dataDir: Path) {
        private var zone: ZoneId = ZoneId.systemDefault()
        private var shutdownGrace: Duration = 15.seconds
        private var startTimeout: Duration = 30.seconds
        private var name: String = "alexandrite"

        public fun zone(zone: ZoneId): Builder = apply { this.zone = zone }

        public fun shutdownGrace(shutdownGrace: Duration): Builder = apply {
            require(!shutdownGrace.isNegative()) { "The shutdown grace may not be negative, was $shutdownGrace." }
            this.shutdownGrace = shutdownGrace
        }

        public fun startTimeout(startTimeout: Duration): Builder = apply {
            require(startTimeout.isPositive()) { "The start timeout must be positive, was $startTimeout." }
            this.startTimeout = startTimeout
        }

        public fun name(name: String): Builder = apply {
            require(name.isNotBlank()) { "The runtime name may not be blank." }
            this.name = name
        }

        public fun build(): RuntimeConfig = RuntimeConfig(dataDir, zone, shutdownGrace, startTimeout, name)
    }

    public companion object {
        public fun builder(dataDir: Path): Builder = Builder(dataDir)
    }
}
