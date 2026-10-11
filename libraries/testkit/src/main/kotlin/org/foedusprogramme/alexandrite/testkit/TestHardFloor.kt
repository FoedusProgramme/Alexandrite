package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.internal.floor.DenyRoot
import org.foedusprogramme.alexandrite.internal.floor.Floor
import org.foedusprogramme.alexandrite.internal.floor.FloorDecision
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import java.nio.file.Path

/**
 * A hard floor that refuses [protected] and the places that every floor refuses, with [home] as the home directory,
 * and leaves the host's own places open.
 *
 * It refuses every path when [home] is null.
 */
public fun testHardFloor(
    protected: List<Path> = emptyList(),
    home: Path? = Path.of(System.getProperty("user.home")),
): HardFloor = TestHardFloor(home?.let { Floor(protected.map { DenyRoot(it, "a place the test protects") }, it) })

/**
 * A hard floor that refuses the host's places in [paths], [protected] and the places that every floor refuses, with
 * [home] as the home directory.
 *
 * It carves no agent's workspace or persona directory out of these places, and refuses every path when [home] is null.
 */
public fun testHardFloor(
    paths: HostPaths,
    protected: List<Path> = emptyList(),
    home: Path? = Path.of(System.getProperty("user.home")),
): HardFloor {
    val configurations = listOfNotNull(paths.configFile?.parent, paths.configTarget?.parent, paths.configBackup)
        .distinct()
        .map { DenyRoot(it, "Alexandrite's configuration") }
    val places = configurations +
        DenyRoot(paths.pluginDataRoot, "plugin data") +
        DenyRoot(paths.dataRoot, "Alexandrite's data") +
        DenyRoot(paths.cacheRoot, "Alexandrite's cache") +
        paths.protected.map { DenyRoot(it, "a place the host protects") } +
        protected.map { DenyRoot(it, "a place the test protects") }
    return TestHardFloor(home?.let { Floor(places, it) })
}

/** Refuses every path when [floor] is null. */
private class TestHardFloor(private val floor: Floor?) : HardFloor {
    override fun checkRead(path: Path, agent: AgentId?): FloorCheck {
        val floor = floor ?: return FloorCheck.Denied(UNKNOWN_HOME)
        return when (val decision = floor.check(path)) {
            is FloorDecision.Allowed -> FloorCheck.Allowed(decision.canonical)
            is FloorDecision.Denied -> FloorCheck.Denied(decision.kind)
        }
    }
}

private const val UNKNOWN_HOME = "every path, as the home directory is unknown"
