package org.foedusprogramme.alexandrite.sdk.di

import kotlinx.coroutines.CompletableDeferred
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
    private val startGate: CompletableDeferred<Unit>? = null,
) : Startable,
    AutoCloseable {
    override suspend fun start() {
        events.record("start $name")
        startGate?.await()
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
    managed: Boolean = true,
    key: Key<Service> = svc(name),
    plugin: String = "test",
    failCreate: Boolean = false,
    failStart: Boolean = false,
    failClose: Boolean = false,
    startGate: CompletableDeferred<Unit>? = null,
): Binding<Service> = binding(key, plugin, name, scope, dependencies.toList(), multi, managed) { r ->
    val injected = dependencies.associate { it.site to r.resolve(it) }
    if (failCreate) error("constructor of $name failed")
    events.record("create $name")
    Service(name, events, injected, failStart, failClose, startGate)
}

fun plugin(id: String, vararg bindings: Binding<*>): PluginBindings = PluginBindings(id, bindings.toList())

fun build(vararg bindings: Binding<*>): Container = Container.build(listOf(plugin("test", *bindings)))

fun Resolver.resolve(dependency: Dependency): Any? = when (dependency.kind) {
    DependencyKind.INSTANCE -> get(dependency.key)
    DependencyKind.OPTIONAL -> getOrNull(dependency.key)
    DependencyKind.ALL -> getAll(dependency.key)
    DependencyKind.LAZY -> lazy(dependency.key)
    DependencyKind.PROVIDER -> provider(dependency.key)
}
