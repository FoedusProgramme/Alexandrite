package org.foedusprogramme.alexandrite.sdk.di.container

import kotlinx.coroutines.CompletableDeferred
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.key
import java.util.Collections

class Events {
    private val events = Collections.synchronizedList(mutableListOf<String>())

    fun record(event: String) {
        events += event
    }

    fun all(): List<String> = synchronized(events) { events.toList() }

    fun starting(prefix: String): List<String> = all().filter { it.startsWith(prefix) }

    fun lifecycle(): List<String> = all().filterNot { it.startsWith("create") }
}

class Service(
    val name: String,
    private val events: Events,
    val injected: Map<String, Any?>,
    private val failStart: Boolean = false,
    private val failStop: Boolean = false,
    private val failDestroy: Boolean = false,
    private val startGate: CompletableDeferred<Unit>? = null,
) : Lifecycle {
    override suspend fun onStart() {
        events.record("start $name")
        startGate?.await()
        if (failStart) error("start $name failed")
    }

    override fun onStop() {
        events.record("stop $name")
        if (failStop) error("stop $name failed")
    }

    override fun onDestroy() {
        events.record("destroy $name")
        if (failDestroy) error("destroy $name failed")
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
    failStop: Boolean = false,
    failDestroy: Boolean = false,
    startGate: CompletableDeferred<Unit>? = null,
): Binding<Service> = binding(key, plugin, name, scope, dependencies.toList(), multi, managed) { r ->
    val injected = dependencies.associate { it.site to r.resolve(it) }
    if (failCreate) error("constructor of $name failed")
    events.record("create $name")
    Service(name, events, injected, failStart, failStop, failDestroy, startGate)
}

fun plugin(id: String, vararg bindings: Binding<*>): PluginBindings = PluginBindings(id, bindings.toList())

fun build(vararg bindings: Binding<*>): Container = Container.build(listOf(plugin("test", *bindings)))

private fun Resolver.resolve(dependency: Dependency): Any? = when (dependency.kind) {
    DependencyKind.INSTANCE -> get(dependency.key)
    DependencyKind.OPTIONAL -> getOrNull(dependency.key)
    DependencyKind.ALL -> getAll(dependency.key)
    DependencyKind.LAZY -> lazy(dependency.key)
    DependencyKind.PROVIDER -> provider(dependency.key)
}
