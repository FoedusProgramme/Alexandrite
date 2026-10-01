package org.foedusprogramme.alexandrite.sdk.di

/** Which container creates and owns an instance. */
public enum class Scope {
    /** One instance per root container. */
    SINGLETON,

    /** One instance per channel container. */
    CHANNEL,
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

/** A key that [Binding.create] resolves for one of its parameters. */
public data class Dependency(val key: Key<*>, val kind: DependencyKind, val parameter: String)

/** How a [Container] creates the instance for [key]. */
public interface Binding<T : Any> {
    public val key: Key<T>
    public val scope: Scope

    /** Everything [create] may resolve. */
    public val dependencies: List<Dependency>

    /** Whether this contributes to [Resolver.getAll] instead of being the single binding for [key]. */
    public val multi: Boolean

    /** The declaration named in error messages. */
    public val origin: String

    public fun create(resolver: Resolver): T
}

public fun <T : Any> binding(
    key: Key<T>,
    origin: String,
    scope: Scope = Scope.SINGLETON,
    dependencies: List<Dependency> = emptyList(),
    multi: Boolean = false,
    create: (Resolver) -> T,
): Binding<T> = FunctionBinding(key, scope, dependencies, multi, origin, create)

public fun <T : Any> instanceBinding(key: Key<T>, value: T, origin: String, multi: Boolean = false): Binding<T> =
    binding(key, origin, multi = multi) { value }

private class FunctionBinding<T : Any>(
    override val key: Key<T>,
    override val scope: Scope,
    override val dependencies: List<Dependency>,
    override val multi: Boolean,
    override val origin: String,
    private val factory: (Resolver) -> T,
) : Binding<T> {
    override fun create(resolver: Resolver): T = factory(resolver)

    override fun toString(): String = origin
}
