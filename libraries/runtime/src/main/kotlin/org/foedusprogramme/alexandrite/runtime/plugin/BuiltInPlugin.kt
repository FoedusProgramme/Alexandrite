package org.foedusprogramme.alexandrite.runtime.plugin

import dev.drewhamilton.poko.Poko

/** A plugin on the built-in list compiled into the runtime. */
@Poko
public class BuiltInPlugin internal constructor(
    public val indexClass: String,
    public val id: String,
    public val layer: BuiltInLayer,
    public val configRoot: String,
)
