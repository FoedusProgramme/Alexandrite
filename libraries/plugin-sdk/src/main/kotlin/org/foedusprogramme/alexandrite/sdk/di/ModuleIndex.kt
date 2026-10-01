package org.foedusprogramme.alexandrite.sdk.di

/** The bindings one module contributes. */
public interface ModuleIndex {
    /** Unique module name. */
    public val module: String

    public fun bindings(): List<Binding<*>>
}
