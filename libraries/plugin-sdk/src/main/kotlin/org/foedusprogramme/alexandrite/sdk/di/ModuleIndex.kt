package org.foedusprogramme.alexandrite.sdk.di

/** The bindings one module contributes. */
public interface ModuleIndex {
    /** Unique module name. */
    public val module: String

    /** The module's config root for the `enabled` switch, null when the module cannot be switched off. */
    public val configRoot: String? get() = null

    public fun bindings(): List<Binding<*>>
}
