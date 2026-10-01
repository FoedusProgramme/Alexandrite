package org.foedusprogramme.alexandrite.ksp

internal class IndexWriter(
    private val module: String,
    private val configRoot: String,
    private val packageName: String,
) {
    val className = module.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Index"

    fun source(sections: Collection<Section>, components: Collection<Component>): String {
        val bindings = components.sortedBy { it.name }.flatMap(::componentBindings)
        val specs = sections.sortedBy { it.path }.map(::sectionSpec)
        val imports = buildList {
            if (specs.isNotEmpty()) add(CONFIG_SECTION_SPEC)
            add("$DI.Binding")
            if (bindings.isNotEmpty()) addAll(listOf("$DI.Dependency", "$DI.DependencyKind"))
            add(MODULE_INDEX)
            if (bindings.isNotEmpty()) addAll(listOf("$DI.Scope", "$DI.binding"))
            if (bindings.isNotEmpty() || specs.isNotEmpty()) add("$DI.key")
        }
        return buildString {
            appendLine("package ${sourceName(packageName)}")
            appendLine()
            imports.forEach { appendLine("import $it") }
            appendLine()
            appendLine("public class $className : ModuleIndex {")
            appendLine("    override val module: String = ${literal(module)}")
            appendLine()
            appendLine("    override val configRoot: String = ${literal(configRoot)}")
            appendLine()
            appendList("override fun bindings(): List<Binding<*>>", bindings)
            if (specs.isNotEmpty()) {
                appendLine()
                appendList("override fun configSections(): List<ConfigSectionSpec<*>>", specs)
            }
            appendLine("}")
        }
    }

    private fun StringBuilder.appendList(declaration: String, elements: List<String>) {
        if (elements.isEmpty()) {
            appendLine("    $declaration = emptyList()")
            return
        }
        appendLine("    $declaration = listOf(")
        elements.forEach { appendLine(it.prependIndent("        ") + ",") }
        appendLine("    )")
    }

    private fun sectionSpec(section: Section): String = buildString {
        appendLine("ConfigSectionSpec(")
        appendLine("    ${Key(section.type, null).code},")
        appendLine("    path = ${literal(section.path)},")
        appendLine("    deserializer = ${section.type}.serializer(),")
        appendLine("    origin = ${origin(section.name)},")
        append(")")
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
        appendLine("    origin = ${origin(name)},")
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

    private fun origin(name: String): String = literal("$name (module $module)")
}
