package org.foedusprogramme.alexandrite.ksp

internal class IndexWriter(private val module: String, private val configRoot: String) {
    val className = module.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Index"

    fun source(sections: Collection<Section>, components: Collection<Component>): String {
        val bindings = sections.sortedBy { it.name }.map(::sectionBinding) +
            components.sortedBy { it.name }.flatMap(::componentBindings)
        val imports = when {
            bindings.isEmpty() -> listOf("$DI.Binding", MODULE_INDEX)
            sections.isEmpty() -> IMPORTS - CONFIG_SOURCE
            else -> IMPORTS
        }
        return buildString {
            appendLine("package $GENERATED_PACKAGE")
            appendLine()
            imports.forEach { appendLine("import $it") }
            appendLine()
            appendLine("public class $className : ModuleIndex {")
            appendLine("    override val module: String = ${literal(module)}")
            appendLine()
            appendLine("    override val configRoot: String = ${literal(configRoot)}")
            appendLine()
            if (bindings.isEmpty()) {
                appendLine("    override fun bindings(): List<Binding<*>> = emptyList()")
            } else {
                appendLine("    override fun bindings(): List<Binding<*>> = listOf(")
                bindings.forEach { appendLine(it.prependIndent("        ") + ",") }
                appendLine("    )")
            }
            appendLine("}")
        }
    }

    private fun sectionBinding(section: Section): String {
        val source = Key(CONFIG_SOURCE.substringAfterLast('.'), null)
        return binding(
            Key(section.type, null),
            section.name,
            channelScoped = false,
            dependencies = listOf(Dependency(source, Kind.INSTANCE, "configSource")),
            create = "{ r -> r.get(${source.code}).section(${literal(section.path)}, ${section.type}.serializer()) }",
            flags = listOf("managed = false"),
        )
    }

    private fun componentBindings(component: Component): List<String> {
        val create = if (component.dependencies.isEmpty()) {
            "{ ${component.key.type}() }"
        } else {
            buildString {
                appendLine("{ r ->")
                appendLine("    ${component.key.type}(")
                component.dependencies.forEach { appendLine("        r.${it.kind.resolverFunction}(${it.key.code}),") }
                appendLine("    )")
                append("}")
            }
        }
        val own = binding(component.key, component.name, component.channelScoped, component.dependencies, create)
        return listOf(own) +
            component.binds.map { alias(component, it, "@Binds") } +
            component.contributes.map { alias(component, it, "@Contribute", "multi = true") }
    }

    private fun alias(component: Component, key: Key, label: String, vararg flags: String): String = binding(
        key,
        component.name,
        component.channelScoped,
        dependencies = listOf(Dependency(component.key, Kind.INSTANCE, label)),
        create = "{ r -> r.get(${component.key.code}) }",
        flags = flags.toList() + "managed = false",
    )

    private fun binding(
        key: Key,
        name: String,
        channelScoped: Boolean,
        dependencies: List<Dependency>,
        create: String,
        flags: List<String> = emptyList(),
    ): String = buildString {
        appendLine("binding(")
        appendLine("    ${key.code},")
        appendLine("    origin = ${literal("$name (module $module)")},")
        appendLine("    scope = Scope.${if (channelScoped) "CHANNEL" else "SINGLETON"},")
        if (dependencies.isNotEmpty()) {
            appendLine("    dependencies = listOf(")
            for (dependency in dependencies) {
                val parameter = literal(dependency.parameter)
                appendLine("        Dependency(${dependency.key.code}, DependencyKind.${dependency.kind}, $parameter),")
            }
            appendLine("    ),")
        }
        flags.forEach { appendLine("    $it,") }
        append(") $create")
    }
}

private val IMPORTS = listOf(
    CONFIG_SOURCE,
    "$DI.Binding",
    "$DI.Dependency",
    "$DI.DependencyKind",
    MODULE_INDEX,
    "$DI.Scope",
    "$DI.binding",
    "$DI.key",
)
