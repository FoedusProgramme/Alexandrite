package org.foedusprogramme.alexandrite.sdk.di

import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.AMBIGUOUS
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.CLOSED
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.CONFLICTING
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.CREATION_FAILED
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.CYCLE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.DUPLICATE_MODULE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.MISSING
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.MODULE_MISMATCH
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.NESTED_CHILD
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.REENTRANT
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.SCOPE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.STARTED_TWICE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.UNDECLARED
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.UNKNOWN_MODULE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.UNLISTED_MODULE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.WRONG_KIND

internal object Problems {
    fun report(site: Site, problems: List<Problem>): DiException {
        val count = if (problems.size == 1) "1 problem" else "${problems.size} problems"
        val message = "Cannot build ${site.label} ($count):" + problems.joinToString("") { "\n- ${it.message}" }
        return DiException(message, problems)
    }

    fun duplicateModule(module: String, indexes: List<ModuleIndex>): Problem = Problem(
        DUPLICATE_MODULE,
        "Duplicate module '$module': loaded by ${indexes.joinToString(" and ") { it::class.java.name }}. " +
            "Keep only one of them on the classpath.",
        module,
        null,
    )

    fun moduleMismatch(index: ModuleIndex, binding: Binding<*>): Problem = Problem(
        MODULE_MISMATCH,
        "Module mismatch: the index of module '${index.module}' (${index::class.java.name}) returns " +
            "${binding.origin}, which belongs to module '${binding.module}'. " +
            "Return only bindings of module '${index.module}' from it.",
        index.module,
        binding.key,
    )

    fun ambiguous(key: Key<*>, bound: List<Node>, site: Site): Problem {
        val remedy = if (site.child) "" else ", or override the key"
        return Problem(
            AMBIGUOUS,
            "Ambiguous binding: $key is bound by ${origins(bound, " and ")}. Remove all but one of them$remedy.",
            soleModule(bound),
            key,
        )
    }

    fun conflicting(key: Key<*>, singles: List<Node>, contributions: List<Node>): Problem = Problem(
        CONFLICTING,
        "Conflicting bindings: $key has a single binding from ${origins(singles)} " +
            "and multibinding contributions from ${origins(contributions)}. " +
            "Bind it either once or only as contributions.",
        soleModule(singles + contributions),
        key,
    )

    fun missing(node: Node, dependency: Dependency, site: Site): Problem {
        val remedy = if (site.child) "pass it to child()" else "pass an override to Container.build()"
        return Problem(
            MISSING,
            "Missing binding: nothing binds ${dependency.key}, which ${node.origin} needs for parameter " +
                "'${dependency.parameter}'. ${loaded(site)} Bind it in one of them or $remedy.",
            node.module,
            dependency.key,
        )
    }

    fun unlistedModule(node: Node, dependency: Dependency, target: Node, site: Site): Problem = Problem(
        UNLISTED_MODULE,
        "Unlisted module: module '${node.module}' needs ${dependency.key} from module '${target.module}': " +
            "${node.origin} injects it as parameter '${dependency.parameter}' and only ${target.origin} binds it, " +
            "but ${site.label} was not created for module '${target.module}'. " +
            "List '${target.module}' in the modules passed to child() or drop the dependency.",
        node.module,
        dependency.key,
    )

    fun unknownModules(site: Site, modules: Collection<String>): Problem {
        val names = modules.joinToString { "'$it'" }
        val which = if (modules.size == 1) "module $names, which is" else "modules $names, which are"
        return Problem(
            UNKNOWN_MODULE,
            "Unknown module: ${site.label} was created for $which not loaded. ${loaded(site)}",
            null,
            null,
        )
    }

    fun allOfSingle(node: Node, dependency: Dependency, single: Node): Problem = Problem(
        WRONG_KIND,
        "Wrong dependency kind: ${node.origin} needs ${wanted(dependency)} for parameter '${dependency.parameter}', " +
            "but ${dependency.key} has a single binding, from ${single.origin}. Inject ${dependency.key} instead.",
        node.module,
        dependency.key,
    )

    fun singleOfMulti(node: Node, dependency: Dependency, contributions: List<Node>): Problem = Problem(
        WRONG_KIND,
        "Wrong dependency kind: ${node.origin} needs ${wanted(dependency)} for parameter '${dependency.parameter}', " +
            "but ${dependency.key} only has multibinding contributions, from ${origins(contributions)}. " +
            "Inject List<${dependency.key}> instead.",
        node.module,
        dependency.key,
    )

    fun scope(node: Node, dependency: Dependency, targets: List<Node>): Problem = Problem(
        SCOPE,
        "Scope violation: singleton ${node.origin} depends on channel-instance-scoped ${origins(targets)} " +
            "through parameter '${dependency.parameter}'. " +
            "Make ${node.origin} channel-instance-scoped or drop the dependency.",
        node.module,
        dependency.key,
    )

    fun cycle(path: List<Node>, parameters: List<String>): Problem {
        val through = if (parameters.size == 1) "parameter" else "parameters"
        return Problem(
            CYCLE,
            "Dependency cycle: ${path.joinToString(" -> ") { it.origin }}, " +
                "through $through ${parameters.joinToString { "'$it'" }}. Inject one of them as Lazy or a provider.",
            soleModule(path),
            path.first().key,
        )
    }

    fun closed(site: Site, key: Key<*>): Problem =
        Problem(CLOSED, "Cannot resolve $key: ${site.label} is closed.", null, key)

    fun closed(site: Site): Problem = Problem(CLOSED, "Cannot start ${site.label}: it is closed.", null, null)

    fun startedTwice(site: Site): Problem = Problem(STARTED_TWICE, "Cannot start ${site.label} twice.", null, null)

    fun unbound(key: Key<*>, site: Site): Problem =
        Problem(MISSING, "Nothing binds $key in ${site.label}. ${loaded(site)}", null, key)

    fun unlisted(key: Key<*>, target: Node, site: Site): Problem = Problem(
        UNLISTED_MODULE,
        "Unlisted module: $key is only bound in module '${target.module}', by ${target.origin}, " +
            "which ${site.label} was not created for. " +
            "Resolve it from a channel instance container created for module '${target.module}'.",
        null,
        key,
    )

    fun notSingle(key: Key<*>, contributions: List<Node>): Problem = Problem(
        WRONG_KIND,
        "$key only has multibinding contributions, from ${origins(contributions)}. Resolve it with getAll().",
        null,
        key,
    )

    fun notMulti(key: Key<*>, single: Node): Problem = Problem(
        WRONG_KIND,
        "$key has a single binding, from ${single.origin}. Resolve it with get(), not getAll().",
        null,
        key,
    )

    fun channelInstanceScoped(node: Node): Problem = Problem(
        SCOPE,
        "${node.key} is channel-instance-scoped, bound by ${node.origin}. " +
            "Resolve it from a channel instance container created with child().",
        null,
        node.key,
    )

    fun undeclared(binding: Binding<*>, key: Key<*>, kind: DependencyKind): Problem = Problem(
        UNDECLARED,
        "${binding.origin} resolved $key as $kind without declaring it. " +
            "Add it to the binding's dependencies with DependencyKind.$kind.",
        binding.module,
        key,
    )

    fun reentrant(node: Node): Problem = Problem(
        REENTRANT,
        "${node.origin} was resolved during its own creation, through a Lazy or provider used in a constructor. " +
            "Use it only after construction.",
        node.module,
        node.key,
    )

    fun creationFailed(node: Node, cause: Exception): Problem =
        Problem(CREATION_FAILED, "Cannot create ${node.key} with ${node.origin}: $cause", node.module, node.key)

    fun nestedChild(site: Site, name: String): Problem = Problem(
        NESTED_CHILD,
        "Cannot create channel instance container '$name' inside ${site.label}. Call child() on the root container.",
        null,
        null,
    )

    fun closedParent(site: Site, name: String): Problem = Problem(
        CLOSED,
        "Cannot create channel instance container '$name': ${site.label} is closed.",
        null,
        null,
    )

    private fun wanted(dependency: Dependency): String = when (dependency.kind) {
        DependencyKind.INSTANCE -> "${dependency.key}"
        DependencyKind.OPTIONAL -> "${dependency.key}?"
        DependencyKind.ALL -> "List<${dependency.key}>"
        DependencyKind.LAZY -> "Lazy<${dependency.key}>"
        DependencyKind.PROVIDER -> "() -> ${dependency.key}"
    }

    private fun origins(nodes: List<Node>, separator: String = ", "): String =
        nodes.joinToString(separator) { it.origin }

    private fun soleModule(nodes: List<Node>): String? = nodes.map { it.module }.distinct().singleOrNull()

    private fun loaded(site: Site): String = "Loaded modules: ${site.modules.joinToString().ifEmpty { "none" }}."
}
