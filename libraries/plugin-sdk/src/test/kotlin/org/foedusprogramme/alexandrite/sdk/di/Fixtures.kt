package org.foedusprogramme.alexandrite.sdk.di

import java.util.Collections

class Events {
    private val events = Collections.synchronizedList(mutableListOf<String>())

    fun record(event: String) {
        events += event
    }

    fun all(): List<String> = synchronized(events) { events.toList() }

    fun starting(prefix: String): List<String> = all().filter { it.startsWith(prefix) }
}

class Service(
    val name: String,
    private val events: Events,
    val injected: Map<String, Any?>,
    private val failStart: Boolean,
    private val failClose: Boolean,
) : Startable,
    AutoCloseable {
    override suspend fun start() {
        events.record("start $name")
        if (failStart) error("start $name failed")
    }

    override fun close() {
        events.record("close $name")
        if (failClose) error("close $name failed")
    }

    fun dependency(parameter: String): Service = injected.getValue(parameter) as Service

    override fun toString(): String = name
}

fun svc(name: String): Key<Service> = key(name)

fun dep(name: String, kind: DependencyKind = DependencyKind.INSTANCE): Dependency = Dependency(svc(name), kind, name)

fun service(
    name: String,
    vararg dependencies: Dependency,
    events: Events = Events(),
    scope: Scope = Scope.SINGLETON,
    multi: Boolean = false,
    key: Key<Service> = svc(name),
    module: String = "test",
    failCreate: Boolean = false,
    failStart: Boolean = false,
    failClose: Boolean = false,
): Binding<Service> = binding(key, "$name (module $module)", scope, dependencies.toList(), multi) { resolver ->
    val injected = dependencies.associate { it.parameter to resolver.resolve(it) }
    if (failCreate) error("constructor of $name failed")
    events.record("create $name")
    Service(name, events, injected, failStart, failClose)
}

fun index(module: String, vararg bindings: Binding<*>): ModuleIndex = object : ModuleIndex {
    override val module: String = module

    override fun bindings(): List<Binding<*>> = bindings.toList()
}

fun build(vararg bindings: Binding<*>): Container = Container.build(listOf(index("test", *bindings)))

fun Resolver.resolve(dependency: Dependency): Any? = when (dependency.kind) {
    DependencyKind.INSTANCE -> get(dependency.key)
    DependencyKind.OPTIONAL -> getOrNull(dependency.key)
    DependencyKind.ALL -> getAll(dependency.key)
    DependencyKind.LAZY -> lazy(dependency.key)
    DependencyKind.PROVIDER -> provider(dependency.key)
}
