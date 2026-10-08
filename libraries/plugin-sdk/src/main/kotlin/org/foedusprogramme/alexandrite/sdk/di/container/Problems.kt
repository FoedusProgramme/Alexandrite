@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.alexandrite.sdk.di.container

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.AMBIGUOUS
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CLOSED
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CONFLICTING
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CREATION_FAILED
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CYCLE
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.DUPLICATE_PLUGIN
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.EXTRA_SCOPE
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.MISSING
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.PLUGIN_MISMATCH
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.REENTRANT
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.SCOPE
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.UNKNOWN_PLUGIN
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.UNLISTED_PLUGIN
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.WRONG_KIND
import org.foedusprogramme.alexandrite.sdk.problem.Problem

internal object Problems {
    fun report(site: Site, problems: List<Problem>): DiException {
        val count = if (problems.size == 1) "1 problem" else "${problems.size} problems"
        val message = "Cannot build ${site.label} ($count):" + problems.joinToString("") { "\n- ${it.message}" }
        return DiException(message, problems)
    }

    fun duplicatePlugin(id: String, count: Int): Problem =
        Problem(DUPLICATE_PLUGIN, "Duplicate plugin '$id': its bindings are passed $count times.", id)

    fun pluginMismatch(id: String, binding: Binding<*>): Problem = Problem(
        PLUGIN_MISMATCH,
        "Plugin mismatch: the bindings of plugin '$id' include ${label(binding)}, " +
            "which belongs to plugin '${binding.plugin}'.",
        id,
    )

    fun ambiguous(key: Key<*>, bound: List<Node>): Problem = Problem(
        AMBIGUOUS,
        "Ambiguous binding: $key is bound by ${labels(bound, " and ")}. Remove all but one of them.",
        solePlugin(bound),
    )

    fun conflicting(key: Key<*>, singles: List<Node>, contributions: List<Node>): Problem = Problem(
        CONFLICTING,
        "Conflicting bindings: $key has a single binding from ${labels(singles)} " +
            "and multibinding contributions from ${labels(contributions)}. " +
            "Bind it either once or only as contributions.",
        solePlugin(singles + contributions),
    )

    fun missing(node: Node, dependency: Dependency, site: Site): Problem = Problem(
        MISSING,
        "Missing binding: nothing binds ${dependency.key}, which ${node.label} needs for parameter " +
            "'${dependency.site}'. ${loaded(site)}",
        node.plugin,
    )

    fun extraScope(binding: Binding<*>): Problem = Problem(
        EXTRA_SCOPE,
        "Wrong scope: ${label(binding)} is added to a channel instance container, " +
            "but is not channel-instance-scoped.",
        binding.plugin,
    )

    fun unlistedPlugin(node: Node, dependency: Dependency, target: Node): Problem = Problem(
        UNLISTED_PLUGIN,
        "Unlisted plugin: plugin '${node.plugin}' needs ${dependency.key} from plugin '${target.plugin}': " +
            "${node.label} injects it as parameter '${dependency.site}' and only ${target.label} binds it, " +
            "but the channel instance container is not for plugin '${target.plugin}'.",
        node.plugin,
    )

    fun unknownPlugins(site: Site, plugins: Collection<String>): Problem {
        val names = plugins.joinToString { "'$it'" }
        val message = if (plugins.size == 1) "Unknown plugin: $names is" else "Unknown plugins: $names are"
        return Problem(UNKNOWN_PLUGIN, "$message not loaded. ${loaded(site)}", null)
    }

    fun wrongKind(node: Node, dependency: Dependency, bound: List<Node>): Problem = wrongKind(
        dependency.key,
        "${node.label} needs ${wanted(dependency)} for parameter '${dependency.site}'",
        bound,
        node.plugin,
    )

    fun wrongKind(key: Key<*>, all: Boolean, bound: List<Node>): Problem = wrongKind(
        key,
        if (all) "resolving every contribution to $key" else "resolving one instance of $key",
        bound,
        null,
    )

    fun scope(node: Node, dependency: Dependency, targets: List<Node>): Problem = Problem(
        SCOPE,
        "Scope violation: singleton ${node.label} depends on channel-instance-scoped ${labels(targets)} " +
            "through parameter '${dependency.site}'. " +
            "Make ${node.origin} channel-instance-scoped or drop the dependency.",
        node.plugin,
    )

    fun unreachable(node: Node, dependency: Dependency, contributions: List<Node>): Problem {
        val (contribute, them) =
            if (contributions.size == 1) "contributes" to "it a singleton" else "contribute" to "them singletons"
        return Problem(
            SCOPE,
            "Scope violation: channel-instance-scoped ${labels(contributions)} $contribute to ${dependency.key}, " +
                "which singleton ${node.label} collects through parameter '${dependency.site}'. " +
                "Make $them or make ${node.origin} channel-instance-scoped.",
            solePlugin(contributions),
        )
    }

    fun cycle(path: List<Node>, sites: List<String>): Problem {
        val through = if (sites.size == 1) "parameter" else "parameters"
        return Problem(
            CYCLE,
            "Dependency cycle: ${path.joinToString(" -> ") { it.label }}, " +
                "through $through ${sites.joinToString { "'$it'" }}. Inject one of them as Lazy or a provider.",
            solePlugin(path),
        )
    }

    fun closed(site: Site, key: Key<*>): Problem =
        Problem(CLOSED, "Cannot resolve $key: ${site.label} is closed.", null)

    fun closed(site: Site, action: String): Problem =
        Problem(CLOSED, "Cannot $action ${site.label}: it is closed.", null)

    fun startedTwice(site: Site): String = "Cannot start ${site.label} twice."

    fun openedTwice(site: Site): String = "Cannot open ${site.label} twice."

    fun busy(site: Site, action: String, running: String): String =
        "Cannot $action ${site.label} while its $running step runs."

    fun unbound(key: Key<*>, site: Site): Problem =
        Problem(MISSING, "Nothing binds $key in ${site.label}. ${loaded(site)}", null)

    fun unlisted(key: Key<*>, target: Node, site: Site): Problem = Problem(
        UNLISTED_PLUGIN,
        "Unlisted plugin: $key is only bound in plugin '${target.plugin}', by ${target.label}, " +
            "which ${site.label} is not for.",
        null,
    )

    fun channelInstanceScoped(node: Node, site: Site): Problem = Problem(
        SCOPE,
        "${node.key} is channel-instance-scoped, bound by ${node.label}, so ${site.label} does not create it.",
        null,
    )

    fun undeclared(binding: Binding<*>, key: Key<*>, kind: DependencyKind): String =
        "${label(binding)} resolved $key as $kind without declaring it. " +
            "Add it to the binding's dependencies with DependencyKind.$kind."

    fun reentrant(node: Node): Problem = Problem(
        REENTRANT,
        "${node.label} was resolved during its own creation, through a Lazy or provider used in a constructor. " +
            "Use it only after construction.",
        node.plugin,
    )

    fun creationFailed(node: Node, cause: Throwable): Problem =
        Problem(CREATION_FAILED, "Cannot create ${node.key} with ${node.label}: $cause", node.plugin)

    fun nestedChild(site: Site, name: String?): String {
        val child = name?.let { "channel instance container '$it'" } ?: "a channel instance container"
        return "Cannot create $child inside ${site.label}: only a root container has channel instance containers."
    }

    fun closedParent(site: Site, name: String): Problem =
        Problem(CLOSED, "Cannot create channel instance container '$name': ${site.label} is closed.", null)

    fun label(binding: Binding<*>): String = "${binding.origin} (plugin ${binding.plugin})"

    private fun wrongKind(key: Key<*>, use: String, bound: List<Node>, plugin: String?): Problem {
        val has = if (bound.first().binding.multi) {
            "only has multibinding contributions, from ${labels(bound)}"
        } else {
            "has a single binding, from ${labels(bound)}"
        }
        return Problem(WRONG_KIND, "Wrong dependency kind: $use, but $key $has.", plugin)
    }

    private fun wanted(dependency: Dependency): String = when (dependency.kind) {
        DependencyKind.INSTANCE -> "${dependency.key}"
        DependencyKind.OPTIONAL -> "${dependency.key}?"
        DependencyKind.ALL -> "List<${dependency.key}>"
        DependencyKind.LAZY -> "Lazy<${dependency.key}>"
        DependencyKind.PROVIDER -> "() -> ${dependency.key}"
    }

    private fun labels(nodes: List<Node>, separator: String = ", "): String = nodes.joinToString(separator) { it.label }

    private fun solePlugin(nodes: List<Node>): String? = nodes.map { it.plugin }.distinct().singleOrNull()

    private fun loaded(site: Site): String = "Loaded plugins: ${site.plugins.joinToString().ifEmpty { "none" }}."
}
