package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** The SDK API version the descriptor records. */
internal const val SDK_API_VERSION = 1

/** Writes the index of a plugin, its service file and its descriptor. */
internal class IndexWriter(
    private val options: PluginOptions,
    /** The fully qualified name of the index class. */
    private val indexClass: String,
    private val entry: PluginEntry,
) {
    private val packageName = indexClass.substringBeforeLast('.')
    private val className = indexClass.substringAfterLast('.')

    fun write(
        codeGenerator: CodeGenerator,
        dependencies: Dependencies,
        sections: Collection<Section>,
        components: Collection<Component>,
    ) {
        codeGenerator.createNewFile(dependencies, packageName, className).writer().use {
            it.write(source(sections, components))
        }
        codeGenerator.createNewFileByPath(dependencies, "META-INF/services/$PLUGIN_INDEX", "").writer().use {
            it.write("$indexClass\n")
        }
        codeGenerator.createNewFileByPath(dependencies, "META-INF/alexandrite/${options.id}", "json").writer().use {
            it.write(descriptor())
        }
    }

    private fun source(sections: Collection<Section>, components: Collection<Component>): String {
        val bindings = components.sortedBy { it.label }.flatMap(::componentBindings)
        val specs = sections.sortedBy { it.path }.map(::sectionSpec)
        val imports = listOf(
            ALEXANDRITE_SDK,
            CONFIG_SECTION_SPEC,
            "$DI.Binding",
            "$DI.Dependency",
            "$DI.DependencyKind",
            "$DI.Scope",
            "$DI.binding",
            "$DI.key",
            PLUGIN_INDEX,
            PLUGIN_INFO,
        )
        return buildString {
            appendLine("package ${sourceName(packageName)}")
            appendLine()
            imports.forEach { appendLine("import $it") }
            appendLine()
            appendLine("public class ${sourceName(className)} : PluginIndex {")
            appendLine("    override val info: PluginInfo = PluginInfo(")
            appendLine("        id = ${literal(options.id)},")
            appendLine("        name = ${literal(entry.name)},")
            appendLine("        version = ${literal(options.version)},")
            appendLine("        description = ${literal(entry.description)},")
            appendLine("        sdkApi = AlexandriteSdk.API_VERSION,")
            appendLine("        requires = ${listCode(entry.requires.map(::literal))},")
            appendLine("        entryClass = ${literal(entry.className)},")
            appendLine("    )")
            appendLine()
            appendLine("    override val configRoot: String = ${literal(options.configRoot)}")
            appendLine()
            appendList("override fun bindings(): List<Binding<*>>", bindings)
            appendLine()
            appendList("override fun configSections(): List<ConfigSectionSpec<*>>", specs)
            appendLine("}")
        }
    }

    private fun descriptor(): String {
        val fields = listOf(
            "id" to jsonString(options.id),
            "name" to jsonString(entry.name),
            "version" to jsonString(options.version),
            "description" to jsonString(entry.description),
            "sdkApi" to SDK_API_VERSION.toString(),
            "requires" to entry.requires.joinToString(prefix = "[", postfix = "]") { jsonString(it) },
            "entryClass" to jsonString(entry.className),
            "indexClass" to jsonString(indexClass),
            "configRoot" to jsonString(options.configRoot),
            "builtIn" to options.builtIn.toString(),
        )
        return fields.joinToString(",\n", prefix = "{\n", postfix = "\n}\n") { (name, value) ->
            "  ${jsonString(name)}: $value"
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
        appendLine("    origin = ${literal(section.label)},")
        append(")")
    }

    private fun componentBindings(component: Component): List<String> {
        val create = if (component.dependencies.isEmpty()) {
            "{ ${component.factory}() }"
        } else {
            buildString {
                appendLine("{ $RESOLVER ->")
                appendLine("    ${component.factory}(")
                component.dependencies.forEach {
                    appendLine("        $RESOLVER.${it.kind.resolverFunction}(${it.key.code}),")
                }
                appendLine("    )")
                append("}")
            }
        }
        val own = binding(component.key, component, component.dependencies.map { it.code }, create)
        return listOf(own) +
            component.binds.map { alias(component, it.key, BINDS) } +
            component.contributes.map { alias(component, it.key, CONTRIBUTE, "multi = true") }
    }

    private fun alias(component: Component, key: Key, annotation: String, vararg flags: String): String = binding(
        key,
        component,
        dependencies = listOf(
            dependencyCode(component.key, DependencyKind.INSTANCE, Messages.annotationLabel(annotation)),
        ),
        create = "{ $RESOLVER -> $RESOLVER.get(${component.key.code}) }",
        flags = flags.toList() + "managed = false",
    )

    private fun binding(
        key: Key,
        component: Component,
        dependencies: List<String>,
        create: String,
        flags: List<String> = emptyList(),
    ): String = buildString {
        appendLine("binding(")
        appendLine("    ${key.code},")
        appendLine("    plugin = ${literal(options.id)},")
        appendLine("    origin = ${literal(component.label)},")
        appendLine("    scope = Scope.${if (component.channelInstanceScoped) "CHANNEL_INSTANCE" else "SINGLETON"},")
        if (dependencies.isNotEmpty()) {
            appendLine("    dependencies = listOf(")
            dependencies.forEach { appendLine("        $it,") }
            appendLine("    ),")
        }
        flags.forEach { appendLine("    $it,") }
        append(") $create")
    }

    private val Dependency.code: String get() = dependencyCode(key, kind, site)

    private fun dependencyCode(key: Key, kind: DependencyKind, site: String): String =
        "Dependency(${key.code}, DependencyKind.$kind, ${literal(site)})"

    private val Key.code: String get() = "key<$type>(${qualifier?.let(::literal).orEmpty()})"

    private val DependencyKind.resolverFunction: String
        get() = when (this) {
            DependencyKind.INSTANCE -> "get"
            DependencyKind.OPTIONAL -> "getOrNull"
            DependencyKind.ALL -> "getAll"
            DependencyKind.LAZY -> "lazy"
            DependencyKind.PROVIDER -> "provider"
        }

    private fun listCode(elements: List<String>): String =
        if (elements.isEmpty()) "emptyList()" else elements.joinToString(prefix = "listOf(", postfix = ")")

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (char in value) {
            when {
                char == '"' || char == '\\' -> append('\\').append(char)
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}

private const val RESOLVER = "alexandriteResolver"
