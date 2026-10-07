package org.foedusprogramme.alexandrite.sdk.di.container

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport.Outcome
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport.Step
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeMark

/** Validates a dependency graph, then creates its instances and takes them through their [Lifecycle]. */
@InternalAlexandriteApi
public class Container private constructor(
    private val site: Site,
    private val graph: Graph,
    private val parent: Container?,
    /** The plugins a channel instance container is for. */
    private val plugins: Set<String>,
) : Resolver,
    AutoCloseable {
    private val level = if (parent == null) Scope.SINGLETON else Scope.CHANNEL_INSTANCE
    private val instances = ConcurrentHashMap<Node, Any>()
    private val lock = Any()
    private val managed = mutableListOf<Managed>()
    private val creating = mutableSetOf<Node>()
    private val children = LinkedHashSet<Container>()
    private val closed = AtomicBoolean()
    private var started = false
    private var opened = false

    /** The step running over the managed instances, null between steps. */
    private var running: String? = null

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
        enter(START) {
            if (started) throw DiException(Problems.startedTwice(site))
            started = true
        }
        try {
            for (entry in entries { it.instance is Lifecycle }) {
                currentCoroutineContext().ensureActive()
                entry.lifecycle.onStart()
                synchronized(lock) { entry.started = true }
            }
        } finally {
            leave()
        }
    }

    /** Calls [Lifecycle.onOpen] on the started instances in creation order. */
    public suspend fun open() {
        enter(OPEN) {
            if (opened) throw DiException(Problems.openedTwice(site))
            opened = true
        }
        try {
            for (entry in entries { it.started }) {
                currentCoroutineContext().ensureActive()
                entry.lifecycle.onOpen()
                synchronized(lock) { entry.opened = true }
            }
        } finally {
            leave()
        }
    }

    /** Calls [Lifecycle.onClose] and [Lifecycle.onDrain] while [deadline] has not passed, then [Lifecycle.onStop]. */
    public suspend fun stop(deadline: TimeMark): List<StepReport> {
        enter(STOP) {}
        try {
            val reports = mutableListOf<StepReport>()
            for (entry in taking({ it.opened }) { it.opened = false }) {
                reports += entry.report(Step.CLOSE, callBefore(deadline) { entry.lifecycle.onClose() })
            }
            for (entry in taking({ it.started && !it.drained }) { it.drained = true }) {
                reports += entry.report(Step.DRAIN, callBefore(deadline) { entry.lifecycle.onDrain() })
            }
            for (entry in taking({ it.started }) { it.started = false }) {
                val failure = failureOf(entry.lifecycle::onStop)
                if (failure is VirtualMachineError) throw failure
                reports += entry.report(Step.STOP, failure)
            }
            return reports
        } finally {
            leave()
        }
    }

    /** A channel instance container that creates the channel-instance-scoped bindings of [plugins] plus [bindings]. */
    public fun child(name: String, plugins: Set<String>, bindings: List<Binding<*>> = emptyList()): Container {
        if (parent != null) throw DiException(Problems.nestedChild(site, name))
        if (closed.get()) throw DiException(Problems.closedParent(site, name))
        val childSite = Site("channel instance container '$name'", site.plugins, child = true)
        val problems = mutableListOf<Problem>()
        val child = Container(childSite, childGraph(plugins, bindings, childSite, problems), this, plugins)
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

    /** Destroys live children, then stops and destroys the managed instances in reverse creation order. */
    public fun destroy(): List<StepReport> {
        val (live, own) = synchronized(lock) {
            running?.let { throw IllegalStateException(Problems.busy(site, CLOSE, it)) }
            if (!closed.compareAndSet(false, true)) return emptyList()
            children.toList().also { children.clear() } to managed.asReversed().toList()
        }
        val reports = mutableListOf<StepReport>()
        for (child in live.asReversed()) {
            try {
                reports += child.destroy()
            } catch (e: Throwable) {
                val owner = child.plugins.sorted().joinToString()
                reports += StepReport(owner, child.site.label, Step.DESTROY, Outcome.Failed(e))
            }
        }
        for (entry in own.filter { it.opened }) {
            reports += entry.report(Step.CLOSE, IllegalStateException(LEFT_OPEN))
        }
        for (entry in own.filter { it.started }) {
            entry.started = false
            reports += entry.report(Step.STOP, failureOf(entry.lifecycle::onStop))
        }
        for (entry in own) reports += entry.report(Step.DESTROY, destroyed(entry.instance))
        parent?.detach(this)
        reports.firstNotNullOfOrNull {
            (it.outcome as? Outcome.Failed)?.error as? VirtualMachineError
        }?.let { throw it }
        return reports
    }

    /** [destroy], throwing the first failure with the others suppressed. */
    override fun close() {
        val failures = destroy().mapNotNull { (it.outcome as? Outcome.Failed)?.error }
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
        val (listed, unlisted) =
            graph.nodes.partition { node -> node.level == Scope.SINGLETON || node.owners.any { it in plugins } }
        val extra = bindings.map { Node(it, Scope.CHANNEL_INSTANCE) }
        return graphOf(listed + extra, childSite, problems, unlisted)
    }

    private fun createAll(problems: List<Problem>) {
        val plan = graph.plan(level, site)
        val all = problems + plan.problems
        if (all.isNotEmpty()) throw Problems.report(site, all)
        try {
            plan.order.forEach(::instanceOf)
        } catch (e: Throwable) {
            closed.set(true)
            val created = synchronized(lock) { managed.asReversed().toList() }
            created.mapNotNull { (destroyed(it.instance) as? Outcome.Failed)?.error }.forEach(e::addSuppressed)
            throw e
        }
    }

    /** Starts the step [action] after [check]. */
    private inline fun enter(action: String, check: () -> Unit) {
        synchronized(lock) {
            if (closed.get()) throw DiException(Problems.closed(site, action))
            running?.let { throw IllegalStateException(Problems.busy(site, action, it)) }
            check()
            running = action
        }
    }

    private fun leave() {
        synchronized(lock) { running = null }
    }

    private fun entries(select: (Managed) -> Boolean): List<Managed> = synchronized(lock) { managed.filter(select) }

    /** The entries [select] picks, last created first, each marked by [mark] just before it is used. */
    private fun taking(select: (Managed) -> Boolean, mark: (Managed) -> Unit): Sequence<Managed> =
        entries(select).asReversed().asSequence().onEach { synchronized(lock) { mark(it) } }

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
            } catch (e: Throwable) {
                if (e is DiException || e is VirtualMachineError || e is InterruptedException) throw e
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
            graph.unlisted[key]?.let { throw DiException(Problems.unlisted(key, it, site)) }
            return null
        }
        if (node.level == Scope.CHANNEL_INSTANCE && parent == null) {
            throw DiException(Problems.channelInstanceScoped(node, site))
        }
        return node
    }

    private fun single(key: Key<*>): Node = singleOrNull(key) ?: throw DiException(Problems.unbound(key, site))

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
            val replaced = bound.filter { it.key in overridden }.groupBy({ it.key }, { it.plugin })
            val nodes = bound.filter { it.key !in overridden }.map { Node(it, it.scope) } +
                overrides.map { Node(it, it.scope, replaced[it.key]?.toSet() ?: setOf(it.plugin)) }
            val graph = graphOf(nodes, site, problems)
            return Container(site, graph, parent = null, plugins = emptySet()).apply { createAll(problems) }
        }
    }
}

private const val START = "start"
private const val OPEN = "open"
private const val STOP = "stop"
private const val CLOSE = "close"

private const val LEFT_OPEN = "Never closed: the container was destroyed before it was stopped."

private class Managed(val instance: Any, val binding: Binding<*>) {
    var started = false
    var opened = false
    var drained = false

    val lifecycle: Lifecycle get() = instance as Lifecycle

    fun report(step: Step, outcome: Outcome): StepReport = StepReport(binding.plugin, binding.origin, step, outcome)

    fun report(step: Step, failure: Throwable?): StepReport =
        report(step, failure?.let(Outcome::Failed) ?: Outcome.Completed)
}

/** Calls [Lifecycle.onDestroy], then [AutoCloseable.close], on [instance]. */
private fun destroyed(instance: Any): Outcome {
    val failures = listOfNotNull(
        (instance as? Lifecycle)?.let { failureOf(it::onDestroy) },
        (instance as? AutoCloseable)?.let { failureOf(it::close) },
    )
    val first = failures.firstOrNull() ?: return Outcome.Completed
    failures.drop(1).forEach(first::addSuppressed)
    return Outcome.Failed(first)
}

private inline fun failureOf(block: () -> Unit): Throwable? = try {
    block()
    null
} catch (e: Throwable) {
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
