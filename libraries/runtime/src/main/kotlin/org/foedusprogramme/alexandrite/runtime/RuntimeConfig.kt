package org.foedusprogramme.alexandrite.runtime

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Settings of the runtime itself. */
@Poko
public class RuntimeConfig private constructor(
    public val dataDir: Path,
    /** The root of the plugins' cache directories. */
    public val cacheDir: Path,
    public val zone: ZoneId,
    public val shutdownGrace: Duration,
    /** How long the start stages may take. */
    public val startTimeout: Duration,
    /** Names the instance in messages. */
    public val name: String,
    /** Runs the runtime's coroutines. */
    public val dispatcher: CoroutineDispatcher,
) {
    public class Builder internal constructor(private val dataDir: Path) {
        private var cacheDir: Path = dataDir.resolve("cache")
        private var zone: ZoneId = ZoneId.systemDefault()
        private var shutdownGrace: Duration = 15.seconds
        private var startTimeout: Duration = 30.seconds
        private var name: String = "alexandrite"
        private var dispatcher: CoroutineDispatcher = Dispatchers.Default

        public fun cacheDir(cacheDir: Path): Builder = apply { this.cacheDir = cacheDir }

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

        public fun dispatcher(dispatcher: CoroutineDispatcher): Builder = apply { this.dispatcher = dispatcher }

        public fun build(): RuntimeConfig {
            require(!dataDir.toAbsolutePath().normalize().startsWith(cacheDir.toAbsolutePath().normalize())) {
                "The cache directory may not be the data directory or hold it, was '$cacheDir' for the data " +
                    "directory '$dataDir'."
            }
            return RuntimeConfig(dataDir, cacheDir, zone, shutdownGrace, startTimeout, name, dispatcher)
        }
    }

    public companion object {
        public fun builder(dataDir: Path): Builder = Builder(dataDir)
    }
}
