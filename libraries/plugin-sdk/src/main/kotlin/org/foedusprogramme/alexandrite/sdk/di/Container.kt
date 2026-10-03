package org.foedusprogramme.alexandrite.sdk.di

import org.foedusprogramme.alexandrite.sdk.problem.Problem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Validates a dependency graph, then creates, starts and closes its instances. */
public class Container private constructor(
    private val site: Site,
    private val graph: Graph,
    private val parent: Container?,
) : Resolver,
    AutoCloseable {
    private val level = if (parent == null) Scope.SINGLETON else Scope.CHANNEL_INSTANCE
    private val instances = ConcurrentHashMap<Node, Any>()
    private val lock = Any()
    private val managed = mutableListOf<Any>()
    private val creating = mutableSetOf<Node>()
    private val children = LinkedHashSet<Container>()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    override fun <T : Any> get(key: Key<T>): T = cast(instanceOf(single(key)))

    override fun <T : Any> getOrNull(key: Key<T>): T? = singleOrNull(key)?.let { cast(instanceOf(it)) }

    override fun <T : Any> getAll(key: Key<T>): List<T> {
        ensureOpen(key)
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

    /** Starts every managed [Startable] in creation order. */
    public suspend fun start() {
        if (closed.get()) throw DiException(Problems.closed(site))
        if (!started.compareAndSet(false, true)) throw DiException(Problems.startedTwice(site))
        val done = mutableListOf<Any>()
        for (instance in synchronized(lock) { managed.toList() }) {
            if (instance !is Startable) continue
            if (synchronized(lock) { closed.get() }) throw DiException(Problems.closed(site))
            try {
                instance.start()
            } catch (e: Exception) {
                closeEach(release(done).asReversed()).forEach(e::addSuppressed)
                throw e
            }
            done += instance
        }
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

    /** Closes live children, then every managed [AutoCloseable] in reverse creation order. */
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
        failures += closeEach(own)
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
            closeEach(synchronized(lock) { managed.asReversed().toList() }).forEach(e::addSuppressed)
            throw e
        }
    }

    /** Takes [instances] out of the managed ones, or none when [close] has taken them. */
    private fun release(instances: List<Any>): List<Any> = synchronized(lock) {
        if (closed.get()) return emptyList()
        managed.removeAll { candidate -> instances.any { it === candidate } }
        instances
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
            if (node.binding.managed && managed.none { it === instance }) managed += instance
            return instance
        } finally {
            creating.remove(node)
        }
    }

    private fun ensureOpen(key: Key<*>) {
        if (closed.get()) throw DiException(Problems.closed(site, key))
    }

    /** The single binding for [key], or null when nothing binds it. */
    private fun singleOrNull(key: Key<*>): Node? {
        ensureOpen(key)
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

private fun closeEach(instances: List<Any>): List<Exception> {
    val failures = mutableListOf<Exception>()
    for (instance in instances) {
        if (instance !is AutoCloseable) continue
        try {
            instance.close()
        } catch (e: Exception) {
            failures += e
        }
    }
    return failures
}
