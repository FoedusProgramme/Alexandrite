package org.foedusprogramme.alexandrite.sdk.di

/** A container as error messages describe it. */
internal class Site(val label: String, val modules: List<String>, val child: Boolean)

/** A binding placed in a graph, created by the container of its [level]. */
internal class Node(val binding: Binding<*>, val level: Scope) {
    val key: Key<*> get() = binding.key
    val module: String get() = binding.module
    val origin: String get() = binding.origin

    fun mayDependOn(target: Node): Boolean = level == Scope.CHANNEL_INSTANCE || target.level == Scope.SINGLETON
}

internal class Plan(val order: List<Node>, val cycles: List<Problem>)

internal class Graph(
    val nodes: List<Node>,
    val singles: Map<Key<*>, Node>,
    val multis: Map<Key<*>, List<Node>>,
    /** Single channel-instance-scoped bindings of the modules a child was not created for. */
    val unlisted: Map<Key<*>, Node>,
) {
    /** Problems with the dependencies of [local]. */
    fun problems(local: List<Node>, site: Site): List<Problem> =
        local.flatMap { node -> node.binding.dependencies.mapNotNull { problem(node, it, site) } }

    /** Topological creation order of [local] and the cycles among them. */
    fun plan(local: List<Node>): Plan {
        val order = mutableListOf<Node>()
        val cycles = mutableListOf<Problem>()
        val finished = mutableMapOf<Node, Boolean>()
        val path = mutableListOf<Node>()
        val parameters = mutableListOf<String>()

        fun visit(node: Node) {
            finished[node] = false
            path += node
            for ((dependency, target) in edges(node)) {
                when (finished[target]) {
                    null -> {
                        parameters += dependency.parameter
                        visit(target)
                        parameters.removeAt(parameters.lastIndex)
                    }

                    false -> {
                        val start = path.indexOf(target)
                        cycles +=
                            Problems.cycle(path.drop(start) + target, parameters.drop(start) + dependency.parameter)
                    }

                    true -> Unit
                }
            }
            path.removeAt(path.lastIndex)
            finished[node] = true
            order += node
        }

        for (node in local) {
            if (node !in finished) visit(node)
        }
        return Plan(order, cycles)
    }

    private fun problem(node: Node, dependency: Dependency, site: Site): Problem? {
        val single = singles[dependency.key]
        val contributions = multis[dependency.key].orEmpty()
        if (dependency.kind == DependencyKind.ALL) {
            if (single != null) return Problems.allOfSingle(node, dependency, single)
            val unreachable = contributions.filterNot(node::mayDependOn)
            return if (unreachable.isEmpty()) null else Problems.scope(node, dependency, unreachable)
        }
        val unlistedSingle = unlisted[dependency.key]
        return when {
            single != null -> if (node.mayDependOn(single)) null else Problems.scope(node, dependency, listOf(single))
            contributions.isNotEmpty() -> Problems.singleOfMulti(node, dependency, contributions)
            unlistedSingle != null -> Problems.unlistedModule(node, dependency, unlistedSingle, site)
            dependency.kind == DependencyKind.OPTIONAL -> null
            else -> Problems.missing(node, dependency, site)
        }
    }

    /** The nodes [node] must be created after. */
    private fun edges(node: Node): List<Pair<Dependency, Node>> = node.binding.dependencies
        .filter { it.kind in CREATION_KINDS }
        .flatMap { dependency -> targets(dependency).filter { it.level == node.level }.map { dependency to it } }

    private fun targets(dependency: Dependency): List<Node> = if (dependency.kind == DependencyKind.ALL) {
        multis[dependency.key].orEmpty()
    } else {
        listOfNotNull(singles[dependency.key])
    }
}

/** The graph of [nodes], adding ambiguous and conflicting keys to [problems]. */
internal fun graphOf(
    nodes: List<Node>,
    site: Site,
    problems: MutableList<Problem>,
    unlisted: List<Node> = emptyList(),
): Graph {
    val singles = nodes.filterNot { it.binding.multi }.groupBy { it.key }
    val multis = nodes.filter { it.binding.multi }.groupBy { it.key }
    for ((key, bound) in singles) {
        if (bound.size > 1) problems += Problems.ambiguous(key, bound, site)
        multis[key]?.let { problems += Problems.conflicting(key, bound, it) }
    }
    val unlistedSingles = unlisted.filterNot { it.binding.multi }.associateBy { it.key }
    return Graph(nodes, singles.mapValues { it.value.first() }, multis, unlistedSingles)
}

private val CREATION_KINDS = setOf(DependencyKind.INSTANCE, DependencyKind.OPTIONAL, DependencyKind.ALL)
