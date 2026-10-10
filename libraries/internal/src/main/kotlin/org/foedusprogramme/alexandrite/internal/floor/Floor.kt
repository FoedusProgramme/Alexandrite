package org.foedusprogramme.alexandrite.internal.floor

import org.foedusprogramme.alexandrite.internal.path.CanonicalPath
import org.foedusprogramme.alexandrite.internal.path.NameCase
import org.foedusprogramme.alexandrite.internal.path.PathCanonicalizer
import org.foedusprogramme.alexandrite.internal.path.isAtOrUnder
import org.foedusprogramme.alexandrite.internal.path.nameKey
import java.nio.file.Path

/** A protected place: [path] and everything under it, except what lies in its [carveOuts]. */
public class DenyRoot(
    public val path: Path,
    /** The kind of place that a refusal names. */
    public val kind: String,
    public val carveOuts: List<Path> = emptyList(),
)

/** What [Floor.check] decided. */
public sealed interface FloorDecision {
    /** [canonical] is the path to open. */
    public data class Allowed(val canonical: Path) : FloorDecision

    public data class Denied(val kind: String) : FloorDecision
}

/** How a path meets a protected place. */
public enum class Placement {
    /** The place itself. */
    AT,

    /** A path under the place. */
    INSIDE,

    /** An ancestor of the place. */
    HOLDS,
}

/** A protected place that a path meets: [root] is null for a place that every floor protects. */
public data class Overlap(val kind: String, val placement: Placement, val root: DenyRoot?)

/**
 * The hard floor: refuses every path in a protected place, however it is spelled.
 *
 * Beside [places], every floor protects the credential stores and `.claude*` entries of [home], `/dev`, the sensitive
 * `/proc/<pid>` entries and the macOS magic root entries.
 */
public class Floor(
    places: List<DenyRoot>,
    /** Null when the home directory is unknown. */
    home: Path?,
    private val canonicalizer: PathCanonicalizer = PathCanonicalizer(),
) {
    private val roots: List<Root> = standardRoots(home).map { Root(it, null) } + places.map { Root(it, it) }
    private val home: CanonicalPath? = home?.let(canonicalizer::canonicalize)

    /** [path] (absolute) made canonical and checked, a refusal naming the innermost place it lies in. */
    public fun check(path: Path): FloorDecision {
        val candidate = canonicalizer.canonicalize(path)
        if (isMagic(candidate)) return FloorDecision.Denied(MAGIC)
        roots.filter { root ->
            meetings(candidate, root.path).any { it != Placement.HOLDS } && root.carveOuts.none { isIn(candidate, it) }
        }.maxByOrNull { it.path.real.nameCount }?.let { return FloorDecision.Denied(it.place.kind) }
        if (claudeEntry(candidate) == Placement.INSIDE) return FloorDecision.Denied(CLAUDE)
        if (denyKeys(candidate).any(PROC_SENSITIVE::matches)) return FloorDecision.Denied(PROCESS)
        return FloorDecision.Allowed(candidate.real)
    }

    /** The protected places that [path] (absolute) is, lies in or holds, their carve-outs ignored. */
    public fun overlaps(path: Path): List<Overlap> {
        val candidate = canonicalizer.canonicalize(path)
        val keys = denyKeys(candidate)
        return buildList {
            if (isMagic(candidate)) add(Overlap(MAGIC, Placement.INSIDE, null))
            for (root in roots) {
                meetings(candidate, root.path).sorted().forEach { add(Overlap(root.place.kind, it, root.given)) }
            }
            claudeEntry(candidate)?.let { add(Overlap(CLAUDE, it, null)) }
            when {
                keys.any(PROC_SENSITIVE::matches) -> add(Overlap(PROCESS, Placement.INSIDE, null))
                keys.any(PROC_ANCESTOR::matches) -> add(Overlap(PROCESS, Placement.HOLDS, null))
            }
        }
    }

    /** [place] made canonical: [given] is null for a place that every floor protects. */
    private inner class Root(val place: DenyRoot, val given: DenyRoot?) {
        val path: CanonicalPath = canonicalizer.canonicalize(place.path)
        val carveOuts: List<CanonicalPath> = place.carveOuts.map { carveOut ->
            canonicalizer.canonicalize(carveOut).also {
                require(meetings(it, path) == setOf(Placement.INSIDE)) {
                    "A carve-out lies strictly inside its deny root, was '$carveOut' for '${place.path}'."
                }
            }
        }
    }

    /** INSIDE for a `.claude*` entry of the home directory or a path in one, HOLDS for the home or an ancestor. */
    private fun claudeEntry(candidate: CanonicalPath): Placement? {
        val home = home ?: return null
        val pairs = pairs(candidate, home)
        if (pairs.any { (path, home) -> isAtOrUnder(home, path) }) return Placement.HOLDS
        val entry = pairs.any { (path, home) ->
            val prefix = if (home.endsWith("/")) home else "$home/"
            path.startsWith(prefix) && path.substring(prefix.length).startsWith(CLAUDE_PREFIX)
        }
        return if (entry) Placement.INSIDE else null
    }
}

/** How [path] meets [place] in any spelling that the volumes of either may give them. */
private fun meetings(path: CanonicalPath, place: CanonicalPath): Set<Placement> =
    pairs(path, place).mapNotNullTo(mutableSetOf()) { (key, placeKey) ->
        when {
            key == placeKey -> Placement.AT
            isAtOrUnder(key, placeKey) -> Placement.INSIDE
            isAtOrUnder(placeKey, key) -> Placement.HOLDS
            else -> null
        }
    }

/** The keys of [path] beside those of [other]: verbatim, and case-folded unless both lie on case-sensitive volumes. */
private fun pairs(path: CanonicalPath, other: CanonicalPath): List<Pair<String, String>> {
    val fold = path.nameCase != NameCase.SENSITIVE || other.nameCase != NameCase.SENSITIVE
    val spellings = if (fold) listOf(false, true) else listOf(false)
    return spellings.flatMap { ignoreCase ->
        path.keys(ignoreCase).flatMap { key -> other.keys(ignoreCase).map { key to it } }
    }
}

/** Whether the real form of [path] lies in [carveOut], case-folded only when both lie on case-insensitive volumes. */
private fun isIn(path: CanonicalPath, carveOut: CanonicalPath): Boolean {
    if (isAtOrUnder(path.realKey(false), carveOut.realKey(false))) return true
    val fold = path.nameCase == NameCase.INSENSITIVE && carveOut.nameCase == NameCase.INSENSITIVE
    return fold && isAtOrUnder(path.realKey(true), carveOut.realKey(true))
}

private fun denyKeys(path: CanonicalPath): List<String> =
    path.keys(false) + if (path.nameCase == NameCase.SENSITIVE) emptyList() else path.keys(true)

/** A top-level magic entry, or any top-level dot entry that the filesystem resolves somewhere else. */
private fun isMagic(path: CanonicalPath): Boolean {
    val keys = path.keys(false) + path.keys(true)
    val redirected = path.keys(true).size > 1
    return keys.any { key ->
        key.startsWith("/.") && (redirected || nameKey(key.substring(1).substringBefore('/'), true) in MAGIC_ENTRIES)
    }
}

private fun standardRoots(home: Path?): List<DenyRoot> =
    home?.let { CREDENTIAL_STORES.map { (name, kind) -> DenyRoot(home.resolve(name), kind) } }.orEmpty() +
        DenyRoot(Path.of("/dev"), "devices")

private val CREDENTIAL_STORES = listOf(
    ".ssh" to "SSH keys",
    ".aws" to "cloud credentials",
    ".gnupg" to "GPG keys",
    ".netrc" to "network credentials",
    ".config/gh" to "GitHub credentials",
    ".docker" to "container credentials",
    ".git-credentials" to "git credentials",
    ".npmrc" to "package registry credentials",
    ".kube" to "cluster credentials",
    "Library/Keychains" to "keychains",
)

private const val CLAUDE = "Claude credentials"
private const val CLAUDE_PREFIX = ".claude"
private const val PROCESS = "process memory and environment"
private const val MAGIC = "macOS special paths"

/** Top-level entries that macOS resolves to any file and that real paths do not see through. */
private val MAGIC_ENTRIES = setOf(".vol", ".nofollow", ".resolve", ".file")

private const val PROC_ENTRIES = "environ|cmdline|mem|maps|root|exe|fd"
private val PROC_SENSITIVE = Regex("^/proc/(self|thread-self|\\d+)(/task/\\d+)?/($PROC_ENTRIES)(/.*)?$")
private val PROC_ANCESTOR = Regex("^/(proc(/(self|thread-self|\\d+)(/task(/\\d+)?)?)?/?)?$")
