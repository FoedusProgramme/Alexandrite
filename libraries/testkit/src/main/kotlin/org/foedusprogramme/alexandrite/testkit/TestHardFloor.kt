package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.internal.floor.DenyRoot
import org.foedusprogramme.alexandrite.internal.floor.Floor
import org.foedusprogramme.alexandrite.internal.floor.FloorDecision
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import java.nio.file.Path

/** A hard floor that refuses [protected] and the places that every floor refuses, with [home] as the home directory. */
public fun testHardFloor(
    protected: List<Path> = emptyList(),
    home: Path? = Path.of(System.getProperty("user.home")),
): HardFloor = TestHardFloor(Floor(protected.map { DenyRoot(it, "a place the test protects") }, home))

private class TestHardFloor(private val floor: Floor) : HardFloor {
    override fun check(path: Path): FloorCheck = when (val decision = floor.check(path)) {
        is FloorDecision.Allowed -> FloorCheck.Allowed(decision.canonical)
        is FloorDecision.Denied -> FloorCheck.Denied(decision.kind)
    }
}
