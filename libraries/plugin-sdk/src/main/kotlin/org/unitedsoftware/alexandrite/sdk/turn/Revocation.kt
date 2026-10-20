package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko

/** What revoking a grant did. */
@Poko
public class Revocation(
    /** Whether a grant made at runtime was removed. */
    public val removed: Boolean,
    /** Whether the config file still grants it. */
    public val declared: Boolean,
)
