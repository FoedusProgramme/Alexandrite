package org.foedusprogramme.alexandrite.sdk.di

internal object Problems {
    fun report(site: Site, problems: List<String>): String {
        val count = if (problems.size == 1) "1 problem" else "${problems.size} problems"
        return "Cannot build ${site.label} ($count):" + problems.joinToString("") { "\n- $it" }
    }

    fun duplicateModule(module: String, indexes: List<ModuleIndex>): String =
        "Duplicate module '$module': loaded by ${indexes.joinToString(" and ") { it::class.java.name }}. " +
            "Keep only one of them on the classpath."

    fun ambiguous(key: Key<*>, bound: List<Node>, site: Site): String {
        val remedy = if (site.child) "" else ", or override the key"
        return "Ambiguous binding: $key is bound by ${origins(bound, " and ")}. Remove all but one of them$remedy."
    }

    fun conflicting(key: Key<*>, singles: List<Node>, contributions: List<Node>): String =
        "Conflicting bindings: $key has a single binding from ${origins(singles)} " +
            "and multibinding contributions from ${origins(contributions)}. " +
            "Bind it either once or only as contributions."

    fun missing(node: Node, dependency: Dependency, site: Site): String {
        val remedy = if (site.child) "pass it to child()" else "pass an override to Container.build()"
        return "Missing binding: nothing binds ${dependency.key}, which ${node.origin} needs for parameter " +
            "'${dependency.parameter}'. ${loaded(site)} Bind it in one of them or $remedy."
    }

    fun allOfSingle(node: Node, dependency: Dependency, single: Node): String =
        "Wrong dependency kind: ${node.origin} needs ${wanted(dependency)} for parameter '${dependency.parameter}', " +
            "but ${dependency.key} has a single binding, from ${single.origin}. Inject ${dependency.key} instead."

    fun singleOfMulti(node: Node, dependency: Dependency, contributions: List<Node>): String =
        "Wrong dependency kind: ${node.origin} needs ${wanted(dependency)} for parameter '${dependency.parameter}', " +
            "but ${dependency.key} only has multibinding contributions, from ${origins(contributions)}. " +
            "Inject List<${dependency.key}> instead."

    fun scope(node: Node, dependency: Dependency, targets: List<Node>): String =
        "Scope violation: singleton ${node.origin} depends on channel-scoped ${origins(targets)} " +
            "through parameter '${dependency.parameter}'. Make ${node.origin} channel-scoped or drop the dependency."

    fun cycle(path: List<Node>, parameters: List<String>): String {
        val through = if (parameters.size == 1) "parameter" else "parameters"
        return "Dependency cycle: ${path.joinToString(" -> ") { it.origin }}, " +
            "through $through ${parameters.joinToString { "'$it'" }}. Inject one of them as Lazy or a provider."
    }

    fun closed(site: Site, key: Key<*>): String = "Cannot resolve $key: ${site.label} is closed."

    fun closed(site: Site): String = "Cannot start ${site.label}: it is closed."

    fun startedTwice(site: Site): String = "Cannot start ${site.label} twice."

    fun unbound(key: Key<*>, site: Site): String = "Nothing binds $key in ${site.label}. ${loaded(site)}"

    fun notSingle(key: Key<*>, contributions: List<Node>): String =
        "$key only has multibinding contributions, from ${origins(contributions)}. Resolve it with getAll()."

    fun notMulti(key: Key<*>, single: Node): String =
        "$key has a single binding, from ${single.origin}. Resolve it with get(), not getAll()."

    fun channelScoped(node: Node): String = "${node.key} is channel-scoped, bound by ${node.origin}. " +
        "Resolve it from a channel container created with child()."

    fun undeclared(binding: Binding<*>, key: Key<*>, kind: DependencyKind): String =
        "${binding.origin} resolved $key as $kind without declaring it. " +
            "Add it to the binding's dependencies with DependencyKind.$kind."

    fun reentrant(node: Node): String =
        "${node.origin} was resolved during its own creation, through a Lazy or provider used in a constructor. " +
            "Use it only after construction."

    fun creationFailed(node: Node, cause: Exception): String = "Cannot create ${node.key} with ${node.origin}: $cause"

    fun nestedChild(site: Site, name: String): String =
        "Cannot create channel container '$name' inside ${site.label}. Call child() on the root container."

    fun closedParent(site: Site, name: String): String =
        "Cannot create channel container '$name': ${site.label} is closed."

    private fun wanted(dependency: Dependency): String = when (dependency.kind) {
        DependencyKind.INSTANCE -> "${dependency.key}"
        DependencyKind.OPTIONAL -> "${dependency.key}?"
        DependencyKind.ALL -> "List<${dependency.key}>"
        DependencyKind.LAZY -> "Lazy<${dependency.key}>"
        DependencyKind.PROVIDER -> "() -> ${dependency.key}"
    }

    private fun origins(nodes: List<Node>, separator: String = ", "): String =
        nodes.joinToString(separator) { it.origin }

    private fun loaded(site: Site): String = "Loaded modules: ${site.modules.joinToString().ifEmpty { "none" }}."
}
