package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.path.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** The variable that replaces the address of the releases. */
private const val INSTALL_BASE = "ALEXANDRITE_INSTALL_BASE"

private const val RELEASES = "https://github.com/FoedusProgramme/alexandrite/releases"

private const val CACHE_FILE = "latest-release.json"

private val CACHE_AGE = Duration.ofDays(1)

private val VERSION = Regex(
    "v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
        "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
)

private val logger = LoggerFactory.getLogger("org.unitedsoftware.alexandrite.app.Releases")

/** A release version, ordered by the precedence of semantic versioning. */
internal class ReleaseVersion private constructor(
    private val core: List<String>,
    private val preRelease: List<String>,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        core.zip(other.core).forEach { (a, b) -> compareNumbers(a, b).let { if (it != 0) return it } }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return preRelease.isEmpty().compareTo(other.preRelease.isEmpty())
        }
        preRelease.zip(other.preRelease).forEach { (a, b) -> compareIdentifiers(a, b).let { if (it != 0) return it } }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        /** The version [text] names, with or without a leading `v`, null when it names none. */
        fun parse(text: String): ReleaseVersion? {
            val match = VERSION.matchEntire(text) ?: return null
            val (major, minor, patch, preRelease) = match.destructured
            return ReleaseVersion(listOf(major, minor, patch), preRelease.split('.').filter(String::isNotEmpty))
        }

        private fun compareIdentifiers(a: String, b: String): Int {
            val numeric = a.all { it in '0'..'9' } to b.all { it in '0'..'9' }
            return when {
                numeric.first && numeric.second -> compareNumbers(a, b)
                numeric.first -> -1
                numeric.second -> 1
                else -> a.compareTo(b)
            }
        }

        private fun compareNumbers(a: String, b: String): Int {
            val x = a.trimStart('0')
            val y = b.trimStart('0')
            return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
        }
    }
}

/** Whether [release] comes after [running], false when either is no version. */
internal fun isNewer(release: String, running: String): Boolean {
    val new = ReleaseVersion.parse(release) ?: return false
    val old = ReleaseVersion.parse(running) ?: return false
    return new > old
}

/** Where the releases are, which [environment] may replace. */
internal fun releasesBase(environment: Map<String, String>): String =
    (environment[INSTALL_BASE]?.takeIf { it.isNotEmpty() } ?: RELEASES).trimEnd('/')

internal fun releasePage(base: String, release: String): String = "$base/tag/v$release"

/** The latest release under [base] by where its `latest` page redirects, null when it names none. */
internal fun latestRelease(machine: Machine, base: String): String? {
    val location = machine.redirect("$base/latest") ?: return null
    val tag = location.substringAfterLast("/tag/", "").substringBefore('?').substringBefore('#')
    return tag.removePrefix("v").takeIf { ReleaseVersion.parse(it) != null }
}

@Serializable
private class CachedRelease(val base: String, val latest: String?, val checkedAt: String)

/** The latest release under [base], kept in [cacheDir] for a day. */
internal class LatestRelease(
    private val machine: Machine,
    private val base: String,
    cacheDir: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val file = cacheDir.resolve(CACHE_FILE)

    /** The latest release from the cache while it is fresh, else from a check, null when there is none or no answer. */
    fun get(): String? {
        val cached = cached()
        if (cached != null && fresh(cached)) return cached.latest
        val latest = try {
            latestRelease(machine, base)
        } catch (e: IOException) {
            logger.debug("Cannot tell the latest release of {}: {}", base, "$e")
            return cached?.latest
        }
        store(latest)
        return latest
    }

    private fun fresh(cached: CachedRelease): Boolean {
        val checked = try {
            Instant.parse(cached.checkedAt)
        } catch (e: DateTimeParseException) {
            return false
        }
        val age = Duration.between(checked, clock.instant())
        return cached.base == base && !age.isNegative && age < CACHE_AGE
    }

    private fun cached(): CachedRelease? = try {
        JSON.decodeFromString(CachedRelease.serializer(), Files.readString(file))
    } catch (e: IOException) {
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun store(latest: String?) {
        val text = JSON.encodeToString(CachedRelease.serializer(), CachedRelease(base, latest, "${clock.instant()}"))
        try {
            Files.createDirectories(file.parent)
            writeAtomically(file) { it.write(text.toByteArray()) }
        } catch (e: IOException) {
            logger.debug("Cannot write {}: {}", file, "$e")
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** The release newer than this Alexandrite, null when there is none, no answer or the config turns the checks off. */
internal fun availableRelease(cli: Cli): String? {
    if (!updatesChecked(cli)) return null
    val latest = LatestRelease(cli.machine, releasesBase(cli.environment), cli.locations.cacheDir).get() ?: return null
    return latest.takeIf { isNewer(it, alexandriteVersion) }
}

/** Whether [version] is a build of the sources rather than a release, which no release is meant to replace. */
internal fun isDevelopmentBuild(version: String): Boolean = version.endsWith("-SNAPSHOT")

/** Whether the config file leaves `app.updates.check` on, true when it cannot be read. */
private fun updatesChecked(cli: Cli): Boolean {
    val text = try {
        Files.readString(cli.locations.configFile)
    } catch (e: IOException) {
        return true
    }
    return (writtenConfig(text)?.at("app.updates.check") as? JsonPrimitive)?.booleanOrNull != false
}
