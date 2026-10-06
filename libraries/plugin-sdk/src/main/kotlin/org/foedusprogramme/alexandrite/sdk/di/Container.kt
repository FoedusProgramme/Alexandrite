package org.foedusprogramme.alexandrite.sdk.di

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.sdk.di.StepReport.Outcome
import org.foedusprogramme.alexandrite.sdk.di.StepReport.Step
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeMark

/** Validates a dependency graph, then creates its instances and takes them through their [Lifecycle]. */
public class Container private constructor(
    private val site: Site,
    private val graph: Graph,
    private val parent: Container?,
) : Resolver,
    AutoCloseable {
    private val level = if (parent == null) Scope.SINGLETON else Scope.CHANNEL_INSTANCE
    private val instances = ConcurrentHashMap<Node, Any>()
    private val lock = Any()
    private val managed = mutableListOf<Managed>()
    private val creating = mutableSetOf<Node>()
    private val children = LinkedHashSet<Container>()
    private val started = AtomicBoolean()
    private val opened = AtomicBoolean()
    private val closed = AtomicBoolean()

    override fun <T : Any> get(key: Key<T>): T = cast(instanceOf(single(key)))

    override fun <T : Any> getOrNull(key: Key<T>): T? = singleOrNull(key)?.let { cast(instanceOf(it)) }

    override fun <T : Any> getAll(key: Key<T>): List<T> {
        ensureNotClosed(key)
        graph.singles[key]?.let { throw DiException(Problems.wrongKind(key, all = true, listOf(it))) }
        return graph.multis[key].orEmpty().map { cast(instanceOf(it)) }
    }

    override fun <T : Any> lazy(key: Key<T>): Lazy<T> {
        single(key)
        return kotlin.lazy { get(key) }
    }

    override fun <T : Any> provider(key: Key<T>): () -> T {
        single(key)
        return { get(key) }
    }

    /** Calls [Lifecycle.onStart] on the managed instances in creation order. */
    public suspend fun start() {
        if (closed.get()) throw DiException(Problems.closed(site, START))
        if (!started.compareAndSet(false, true)) throw DiException(Problems.startedTwice(site))
        for (entry in entries { it.instance is Lifecycle }) {
            if (closed.get()) throw DiException(Problems.closed(site, START))
            try {
                currentCoroutineContext().ensureActive()
                entry.lifecycle.onStart()
            } catch (e: Exception) {
                stopEach(takeStarted()).forEach(e::addSuppressed)
                throw e
            }
            synchronized(lock) { if (!closed.get()) entry.started = true }
        }
    }

    /** Calls [Lifecycle.onOpen] on the started instances in creation order. */
    public suspend fun open() {
        if (closed.get()) throw DiException(Problems.closed(site, OPEN))
        if (!opened.compareAndSet(false, true)) throw DiException(Problems.openedTwice(site))
        for (entry in entries { it.started }) {
            if (closed.get()) throw DiException(Problems.closed(site, OPEN))
            try {
                currentCoroutineContext().ensureActive()
                entry.lifecycle.onOpen()
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) closeOpened(e)
                throw e
            }
            synchronized(lock) { entry.opened = true }
        }
    }

    /** Calls [Lifecycle.onClose] and [Lifecycle.onDrain] while [deadline] has not passed, then [Lifecycle.onStop]. */
    public suspend fun stop(deadline: TimeMark): List<StepReport> {
        if (closed.get()) throw DiException(Problems.closed(site, STOP))
        val reports = mutableListOf<StepReport>()
        for (entry in takeOpened()) {
            reports += entry.report(Step.CLOSE, callBefore(deadline) { entry.lifecycle.onClose() })
        }
        for (entry in take({ it.started && !it.drained }) { it.drained = true }) {
            reports += entry.report(Step.DRAIN, callBefore(deadline) { entry.lifecycle.onDrain() })
        }
        for (entry in takeStarted()) {
            val outcome = failureOf(entry.lifecycle::onStop)?.let(Outcome::Failed) ?: Outcome.Completed
            reports += entry.report(Step.STOP, outcome)
        }
        return reports
    }

    /** A channel instance container that creates the channel-instance-scoped bindings of [plugins] plus [bindings]. */
    public fun child(name: String, plugins: Set<String>, bindings: List<Binding<*>> = emptyList()): Container {
        if (parent != null) throw DiException(Problems.nestedChild(site, name))
        if (closed.get()) throw DiException(Problems.closedParent(site, name))
        val childSite = Site("channel instance container '$name'", site.plugins, child = true)
        val problems = mutableListOf<Problem>()
        val child = Container(childSite, childGraph(plugins, bindings, childSite, problems), parent = this)
        child.createAll(problems)
        synchronized(lock) {
            if (!closed.get()) {
                children += child
                return child
            }
        }
        child.close()
        throw DiException(Problems.closedParent(site, name))
    }

    /** The problems [child] would report for [plugins] and [bindings]. */
    public fun validateChild(plugins: Set<String>, bindings: List<Binding<*>> = emptyList()): List<Problem> {
        if (parent != null) throw DiException(Problems.nestedChild(site, null))
        val childSite = Site("channel instance container", site.plugins, child = true)
        val problems = mutableListOf<Problem>()
        val childGraph = childGraph(plugins, bindings, childSite, problems)
        return problems + childGraph.plan(Scope.CHANNEL_INSTANCE, childSite).problems
    }

    /** Closes live children, then stops and destroys the managed instances in reverse creation order. */
    override fun close() {
        val (live, own) = synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            children.toList().also { children.clear() } to managed.asReversed().toList()
        }
        val failures = mutableListOf<Exception>()
        for (child in live.asReversed()) {
            try {
                child.close()
            } catch (e: Exception) {
                failures += e
            }
        }
        failures += stopEach(takeStarted())
        failures += destroyEach(own)
        parent?.detach(this)
        val first = failures.firstOrNull() ?: return
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }

    private fun childGraph(
        plugins: Set<String>,
        bindings: List<Binding<*>>,
        childSite: Site,
        problems: MutableList<Problem>,
    ): Graph {
        val unknown = plugins - site.plugins.toSet() - graph.nodes.mapTo(HashSet()) { it.plugin }
        if (unknown.isNotEmpty()) problems += Problems.unknownPlugins(childSite, unknown)
        bindings.filter { it.scope != Scope.CHANNEL_INSTANCE }.mapTo(problems, Problems::extraScope)
        val (listed, unlisted) = graph.nodes.partition { it.level == Scope.SINGLETON || it.plugin in plugins }
        val extra = bindings.map { Node(it, Scope.CHANNEL_INSTANCE) }
        return graphOf(listed + extra, childSite, problems, unlisted)
    }

    private fun createAll(problems: List<Problem>) {
        val plan = graph.plan(level, site)
        val all = problems + plan.problems
        if (all.isNotEmpty()) throw Problems.report(site, all)
        try {
            plan.order.forEach(::instanceOf)
        } catch (e: Exception) {
            closed.set(true)
            destroyEach(synchronized(lock) { managed.asReversed().toList() }).forEach(e::addSuppressed)
            throw e
        }
    }

    private fun entries(select: (Managed) -> Boolean): List<Managed> = synchronized(lock) { managed.filter(select) }

    /** Marks the entries [select] picks and returns them, last created first. */
    private fun take(select: (Managed) -> Boolean, mark: (Managed) -> Unit): List<Managed> =
        synchronized(lock) { managed.filter(select).onEach(mark).asReversed() }

    private fun takeOpened(): List<Managed> = take({ it.opened }) { it.opened = false }

    private fun takeStarted(): List<Managed> = take({ it.started }) { it.started = false }

    private suspend fun closeOpened(error: Exception) {
        for (entry in takeOpened()) {
            try {
                entry.lifecycle.onClose()
            } catch (e: Exception) {
                error.addSuppressed(e)
            }
        }
    }

    private fun instanceOf(node: Node): Any = when {
        node.level == level -> instances[node] ?: synchronized(lock) { instances[node] ?: create(node) }
        parent != null -> parent.instanceOf(node)
        else -> throw DiException(Problems.channelInstanceScoped(node, site))
    }

    private fun create(node: Node): Any {
        if (closed.get()) throw DiException(Problems.closed(site, node.key))
        if (!creating.add(node)) throw DiException(Problems.reentrant(node))
        try {
            val instance = try {
                node.binding.create(DeclaredResolver(node.binding))
            } catch (e: DiException) {
                throw e
            } catch (e: Exception) {
                throw DiException(Problems.creationFailed(node, e), e)
            }
            instances[node] = instance
            if (node.binding.managed && managed.none { it.instance === instance }) {
                managed += Managed(instance, node.binding)
            }
            return instance
        } finally {
            creating.remove(node)
        }
    }

    private fun ensureNotClosed(key: Key<*>) {
        if (closed.get()) throw DiException(Problems.closed(site, key))
    }

    /** The single binding for [key], or null when nothing binds it. */
    private fun singleOrNull(key: Key<*>): Node? {
        ensureNotClosed(key)
        val node = graph.singles[key]
        if (node == null) {
            graph.multis[key]?.let { throw DiException(Problems.wrongKind(key, all = false, it)) }
            return null
        }
        if (node.level == Scope.CHANNEL_INSTANCE && parent == null) {
            throw DiException(Problems.channelInstanceScoped(node, site))
        }
        return node
    }

    private fun single(key: Key<*>): Node = singleOrNull(key) ?: throw DiException(
        graph.unlisted[key]?.let { Problems.unlisted(key, it, site) } ?: Problems.unbound(key, site),
    )

    private fun detach(child: Container) {
        synchronized(lock) { children.remove(child) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cast(instance: Any): T = instance as T

    /** Resolves only what [binding] declares. */
    private inner class DeclaredResolver(private val binding: Binding<*>) : Resolver {
        override fun <T : Any> get(key: Key<T>): T = this@Container.get(declared(key, DependencyKind.INSTANCE))

        override fun <T : Any> getOrNull(key: Key<T>): T? =
            this@Container.getOrNull(declared(key, DependencyKind.OPTIONAL))

        override fun <T : Any> getAll(key: Key<T>): List<T> = this@Container.getAll(declared(key, DependencyKind.ALL))

        override fun <T : Any> lazy(key: Key<T>): Lazy<T> = this@Container.lazy(declared(key, DependencyKind.LAZY))

        override fun <T : Any> provider(key: Key<T>): () -> T =
            this@Container.provider(declared(key, DependencyKind.PROVIDER))

        private fun <T : Any> declared(key: Key<T>, kind: DependencyKind): Key<T> {
            if (binding.dependencies.none { it.key == key && it.kind == kind }) {
                throw DiException(Problems.undeclared(binding, key, kind))
            }
            return key
        }
    }

    public companion object {
        /**
         * Validates the singletons and their dependencies, then creates them.
         * [overrides] replace what [plugins] bind under their key.
         */
        public fun build(plugins: List<PluginBindings>, overrides: List<Binding<*>> = emptyList()): Container {
            val problems = mutableListOf<Problem>()
            val loaded = plugins.groupBy { it.id }.toSortedMap().map { (id, same) ->
                if (same.size > 1) problems += Problems.duplicatePlugin(id, same.size)
                same.first()
            }
            val site = Site("container 'root'", loaded.map { it.id }, child = false)
            val overridden = overrides.mapTo(HashSet()) { it.key }
            val bound = loaded.flatMap { plugin ->
                plugin.bindings.also { bindings ->
                    bindings.filter {
                        it.plugin != plugin.id
                    }.mapTo(problems) { Problems.pluginMismatch(plugin.id, it) }
                }
            }
            val bindings = bound.filter { it.key !in overridden } + overrides
            val graph = graphOf(bindings.map { Node(it, it.scope) }, site, problems)
            return Container(site, graph, parent = null).apply { createAll(problems) }
        }
    }
}

private const val START = "start"
private const val OPEN = "open"
private const val STOP = "stop"

private class Managed(val instance: Any, val binding: Binding<*>) {
    var started = false
    var opened = false
    var drained = false

    val lifecycle: Lifecycle get() = instance as Lifecycle

    fun report(step: Step, outcome: Outcome): StepReport = StepReport(binding.plugin, binding.origin, step, outcome)
}

private fun stopEach(entries: List<Managed>): List<Exception> = entries.mapNotNull { failureOf(it.lifecycle::onStop) }

private fun destroyEach(entries: List<Managed>): List<Exception> = entries.flatMap { entry ->
    val instance = entry.instance
    listOfNotNull(
        (instance as? Lifecycle)?.let { failureOf(it::onDestroy) },
        (instance as? AutoCloseable)?.let { failureOf(it::close) },
    )
}

private inline fun failureOf(block: () -> Unit): Exception? = try {
    block()
    null
} catch (e: Exception) {
    e
}

/** Runs [block] within the time left until [deadline], rethrowing only the caller's cancellation. */
private suspend fun callBefore(deadline: TimeMark, block: suspend () -> Unit): Outcome {
    val left = -deadline.elapsedNow()
    if (!left.isPositive()) return Outcome.NotCalled
    return try {
        withTimeoutOrNull(left) { block() }?.let { Outcome.Completed } ?: Outcome.TimedOut
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        currentCoroutineContext().ensureActive()
        Outcome.Failed(e)
    }
}
