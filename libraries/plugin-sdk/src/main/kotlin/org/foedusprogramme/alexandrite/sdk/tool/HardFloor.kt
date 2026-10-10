package org.foedusprogramme.alexandrite.sdk.tool

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import java.nio.file.Path

/** Refuses the places that no tool may touch. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface HardFloor {
    /** [path] (absolute) made canonical and checked: the path to open, or why it is refused. */
    public fun check(path: Path): FloorCheck
}

/** What [HardFloor.check] decided. */
public sealed interface FloorCheck {
    @Poko
    public class Allowed(public val canonical: Path) : FloorCheck

    /** [reason] names the kind of protected place, never anything inside it. */
    @Poko
    public class Denied(public val reason: String) : FloorCheck
}
