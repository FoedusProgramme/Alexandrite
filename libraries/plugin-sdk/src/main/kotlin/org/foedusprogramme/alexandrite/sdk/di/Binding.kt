package org.foedusprogramme.alexandrite.sdk.di

import java.util.Objects

/** Which container creates and owns an instance. */
public enum class Scope {
    /** One instance per root container. */
    SINGLETON,

    /** One instance per channel instance container. */
    CHANNEL_INSTANCE,
}

/** How a dependency is injected. */
public enum class DependencyKind {
    /** The instance, which must be bound. */
    INSTANCE,

    /** The instance, or null when nothing binds it. */
    OPTIONAL,

    /** Every multibinding contribution. */
    ALL,

    /** A [Lazy] resolved on first access. */
    LAZY,

    /** A function that resolves on every call. */
    PROVIDER,
}

/** A key that [Binding.create] resolves. */
public class Dependency(
    public val key: Key<*>,
    public val kind: DependencyKind,
    /** The parameter name, or the annotation that adds the dependency. */
    public val site: String,
) {
    override fun equals(other: Any?): Boolean =
        other is Dependency && key == other.key && kind == other.kind && site == other.site

    override fun hashCode(): Int = Objects.hash(key, kind, site)

    override fun toString(): String = "Dependency(key=$key, kind=$kind, site=$site)"
}

/** How a [Container] creates the instance for [key]. */
public interface Binding<T : Any> {
    public val key: Key<T>
    public val scope: Scope

    /** Id of the plugin the binding belongs to. */
    public val plugin: String

    /** Everything [create] may resolve. */
    public val dependencies: List<Dependency>

    /** Whether this contributes to [Resolver.getAll]. */
    public val multi: Boolean

    /** The declaration named in error messages. */
    public val origin: String

    /** Whether the container starts and closes the instance [create] returns. */
    public val managed: Boolean get() = true

    public fun create(resolver: Resolver): T
}

public fun <T : Any> binding(
    key: Key<T>,
    plugin: String,
    origin: String,
    scope: Scope = Scope.SINGLETON,
    dependencies: List<Dependency> = emptyList(),
    multi: Boolean = false,
    managed: Boolean = true,
    create: (Resolver) -> T,
): Binding<T> = FunctionBinding(key, scope, plugin, dependencies, multi, origin, managed, create)

public fun <T : Any> instanceBinding(
    key: Key<T>,
    value: T,
    plugin: String,
    origin: String,
    scope: Scope = Scope.SINGLETON,
    multi: Boolean = false,
): Binding<T> = binding(key, plugin, origin, scope, multi = multi, managed = false) { value }

private class FunctionBinding<T : Any>(
    override val key: Key<T>,
    override val scope: Scope,
    override val plugin: String,
    override val dependencies: List<Dependency>,
    override val multi: Boolean,
    override val origin: String,
    override val managed: Boolean,
    private val factory: (Resolver) -> T,
) : Binding<T> {
    override fun create(resolver: Resolver): T = factory(resolver)

    override fun toString(): String = origin
}
