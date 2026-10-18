package org.unitedsoftware.alexandrite.sdk.config

import dev.drewhamilton.poko.Poko

/** A config value of strings and lists as the config resolves it, where a string that names a variable stays opaque. */
public sealed interface ConfigLiteral {
    /** A string as the config resolves it. */
    @Poko
    public class Text(public val text: String) : ConfigLiteral

    /** A string that inserts an environment variable, as the file writes it. */
    @Poko
    public class Reference(public val written: String) : ConfigLiteral

    @Poko
    public class Items(public val items: List<ConfigLiteral>) : ConfigLiteral
}
