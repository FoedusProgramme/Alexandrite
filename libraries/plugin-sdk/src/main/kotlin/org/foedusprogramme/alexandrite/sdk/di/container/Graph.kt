@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.alexandrite.sdk.di.container

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.problem.Problem

/** A container as error messages describe it. */
internal class Site(val label: String, val plugins: List<String>, val child: Boolean)

/** A binding placed in a graph, created by the container of its [level]. */
internal class Node(
    val binding: Binding<*>,
    val level: Scope,
    /** The plugins whose channel instance containers create a channel-instance-scoped node. */
    val owners: Set<String> = setOf(binding.plugin),
) {
    val key: Key<*> get() = binding.key
    val plugin: String get() = binding.plugin
    val origin: String get() = binding.origin
    val label: String get() = Problems.label(binding)

    fun mayDependOn(target: Node): Boolean = level == Scope.CHANNEL_INSTANCE || target.level == Scope.SINGLETON
}

/** The creation order of the nodes of one level, and the problems that keep them from being created. */
internal class Plan(val order: List<Node>, val problems: List<Problem>)

internal class Graph(
    val nodes: List<Node>,
    val singles: Map<Key<*>, Node>,
    val multis: Map<Key<*>, List<Node>>,
    /** Single channel-instance-scoped bindings of the plugins a child is not for. */
    val unlisted: Map<Key<*>, Node>,
) {
    fun plan(level: Scope, site: Site): Plan {
        val local = nodes.filter { it.level == level }
        val problems = local.flatMapTo(mutableListOf()) { node ->
            node.binding.dependencies.mapNotNull { problem(node, it, site) }
        }
        val order = mutableListOf<Node>()
        val finished = mutableMapOf<Node, Boolean>()
        val path = mutableListOf<Node>()
        val sites = mutableListOf<String>()

        fun visit(node: Node) {
            finished[node] = false
            path += node
            for ((dependency, target) in edges(node)) {
                when (finished[target]) {
                    null -> {
                        sites += dependency.site
                        visit(target)
                        sites.removeAt(sites.lastIndex)
                    }

                    false -> {
                        val start = path.indexOf(target)
                        problems += Problems.cycle(path.drop(start) + target, sites.drop(start) + dependency.site)
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
        return Plan(order, problems)
    }

    private fun problem(node: Node, dependency: Dependency, site: Site): Problem? {
        val single = singles[dependency.key]
        val contributions = multis[dependency.key].orEmpty()
        if (dependency.kind == DependencyKind.ALL) {
            if (single != null) return Problems.wrongKind(node, dependency, listOf(single))
            val unreachable = contributions.filterNot(node::mayDependOn)
            return if (unreachable.isEmpty()) null else Problems.unreachable(node, dependency, unreachable)
        }
        val unlistedSingle = unlisted[dependency.key]
        return when {
            single != null -> if (node.mayDependOn(single)) null else Problems.scope(node, dependency, listOf(single))
            contributions.isNotEmpty() -> Problems.wrongKind(node, dependency, contributions)
            unlistedSingle != null -> Problems.unlistedPlugin(node, dependency, unlistedSingle)
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
        ambiguousGroups(bound, site).forEach { problems += Problems.ambiguous(key, it) }
        multis[key]?.let { problems += Problems.conflicting(key, bound, it) }
    }
    val unlistedSingles = unlisted.filterNot { it.binding.multi }.associateBy { it.key }
    return Graph(nodes, singles.mapValues { it.value.first() }, multis, unlistedSingles)
}

/** The ambiguous groups of [bound], judging the root's channel-instance bindings per plugin. */
private fun ambiguousGroups(bound: List<Node>, site: Site): List<List<Node>> = when {
    bound.size < 2 -> emptyList()
    site.child || bound.any { it.level == Scope.SINGLETON } -> listOf(bound)
    else -> bound.groupBy { it.plugin }.values.filter { it.size > 1 }
}

private val CREATION_KINDS = setOf(DependencyKind.INSTANCE, DependencyKind.OPTIONAL, DependencyKind.ALL)
