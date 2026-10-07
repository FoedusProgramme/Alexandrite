package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.runtime.config.ConfigFile
import org.foedusprogramme.alexandrite.runtime.config.ConfigFileException
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val logger: Logger = LoggerFactory.getLogger("org.foedusprogramme.alexandrite.app")

private const val VERSION_RESOURCE = "/alexandrite-version.properties"

private const val APP_ROOT = "app"

private const val EXAMPLE_CONFIG = "config/alexandrite.example.json"

internal val alexandriteVersion: String by lazy {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream(VERSION_RESOURCE)) {
        "$VERSION_RESOURCE is missing from the classpath"
    }
    val properties = Properties()
    stream.use { properties.load(it) }
    checkNotNull(properties.getProperty("version")) { "$VERSION_RESOURCE has no version" }
}

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.getenv()))
}

/** Runs the host for [args] and returns its exit code. */
internal fun run(
    args: List<String>,
    environment: Map<String, String>,
    osName: String = System.getProperty("os.name"),
    home: Path = Path.of(System.getProperty("user.home")),
    out: PrintStream = System.out,
    err: PrintStream = System.err,
    execute: suspend (RuntimeSpec, suspend AlexandriteRuntime.() -> Unit) -> Termination = { spec, block ->
        AlexandriteRuntime.runUntilSignal(spec, block)
    },
): Int {
    val defaults = locations(Options(), environment, osName, home)
    val options = when (val command = parseArguments(args)) {
        is Command.Run -> command.options

        Command.Help -> {
            out.println(usage(defaults))
            return ExitCode.OK
        }

        Command.Version -> {
            out.println("alexandrite $alexandriteVersion")
            return ExitCode.OK
        }

        is Command.Invalid -> {
            err.println("alexandrite: ${command.message}")
            err.println()
            err.println(usage(defaults))
            return ExitCode.USAGE
        }
    }
    return try {
        host(locations(options, environment, osName, home), environment, err, execute)
    } catch (e: Exception) {
        logger.error("Unexpected error", e)
        err.println("alexandrite: unexpected error: $e")
        ExitCode.FAILURE
    }
}

private fun host(
    locations: Locations,
    environment: Map<String, String>,
    err: PrintStream,
    execute: suspend (RuntimeSpec, suspend AlexandriteRuntime.() -> Unit) -> Termination,
): Int {
    val source = try {
        ConfigFile.read(locations.configFile, environment)
    } catch (e: ConfigFileException.Missing) {
        err.println(
            "alexandrite: ${e.message} Create it from the example at ${exampleConfig()}, or name another file " +
                "with --config or ALEXANDRITE_CONFIG.",
        )
        return ExitCode.CONFIG
    } catch (e: ConfigFileException) {
        err.println("alexandrite: ${e.message}")
        return ExitCode.CONFIG
    }
    val settings = try {
        appConfig(source)
    } catch (e: IllegalArgumentException) {
        err.println("alexandrite: Invalid config at '$APP_ROOT': ${e.message?.lineSequence()?.first()}")
        return ExitCode.CONFIG
    } catch (e: ConfigException) {
        err.println("alexandrite: ${e.message}")
        return ExitCode.CONFIG
    }
    val plugins = try {
        settings.plugins.fold(PluginSet.builtIn()) { set, id -> set.named(id) }
    } catch (e: IllegalArgumentException) {
        err.println("alexandrite: Cannot load the plugins of '$APP_ROOT.plugins': ${e.message}")
        return ExitCode.CONFIG
    }
    val config = try {
        RuntimeConfig.builder(locations.dataDir)
            .cacheDir(locations.cacheDir)
            .zone(settings.zoneId)
            .shutdownGrace(settings.shutdownGraceSeconds.seconds)
            .startTimeout(settings.startTimeoutSeconds.seconds)
            .build()
    } catch (e: IllegalArgumentException) {
        err.println("alexandrite: ${e.message}")
        return ExitCode.CONFIG
    }
    val spec = RuntimeSpec.builder(config, plugins).pluginConfig(source).build()
    logger.info(
        "Alexandrite {} starting with config {}, data {}, cache {}",
        alexandriteVersion,
        locations.configFile,
        locations.dataDir,
        locations.cacheDir,
    )
    return try {
        val termination = runBlocking {
            execute(spec) {
                logger.info("Alexandrite {} is ready", alexandriteVersion)
                awaitCancellation()
            }
        }
        exitCode(termination.request.kind)
    } catch (e: RuntimeStartException) {
        if (e.stopRequest == null) err.println("alexandrite: ${e.message}")
        exitCode(e)
    }
}

private fun appConfig(source: ConfigSource): AppConfig {
    val tree = source.tree(APP_ROOT) ?: JsonObject(emptyMap())
    return Json.decodeFromJsonElement(AppConfig.serializer(), JsonObject(tree - PluginIds.ENABLED_KEY))
}

private fun exampleConfig(): String {
    val jar = try {
        Path.of(AppPlugin::class.java.protectionDomain.codeSource.location.toURI())
    } catch (e: Exception) {
        null
    }
    val example = jar?.parent?.parent?.resolve(EXAMPLE_CONFIG)
    return if (example != null && Files.isRegularFile(example)) "$example" else "$EXAMPLE_CONFIG in the distribution"
}
