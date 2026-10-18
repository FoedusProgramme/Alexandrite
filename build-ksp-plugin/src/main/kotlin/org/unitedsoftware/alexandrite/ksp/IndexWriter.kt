package org.unitedsoftware.alexandrite.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** Writes the index of a plugin, its service file and its descriptor. */
internal class IndexWriter(
    private val options: PluginOptions,
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
        codeGenerator.createNewFileByPath(dependencies, SERVICE_FILE, "").writer().use {
            it.write("$indexClass\n")
        }
        val descriptorPath = "$DESCRIPTOR_DIRECTORY/${options.id}"
        codeGenerator.createNewFileByPath(dependencies, descriptorPath, DESCRIPTOR_EXTENSION).writer().use {
            it.write(descriptor(components))
        }
    }

    /** The fields of [PLUGIN_INFO], which the descriptor repeats. */
    private val infoFields: List<Pair<String, Any>> = listOf(
        "id" to options.id,
        "name" to entry.name,
        "version" to options.version,
        "description" to entry.description,
        "sdkApi" to SDK_API_VERSION,
        "requires" to entry.requires,
        "entryClass" to entry.className,
    )

    private fun source(sections: Collection<Section>, components: Collection<Component>): String {
        val bindings = components.sortedBy { it.origin }.flatMap(::componentBindings)
        val specs = sections.sortedWith(compareBy({ it.channelInstance }, { it.path })).map(::sectionSpec)
        val markers = (components.flatMap { it.markers } + sections.flatMap { it.markers }).toSortedSet() - INTERNAL_API
        val optIns = listOf(simpleName(INTERNAL_API)) + markers.map(::sourceName)
        return buildString {
            appendLine("@file:OptIn(${optIns.joinToString { "$it::class" }})")
            appendLine("@file:Suppress(\"DEPRECATION\")")
            appendLine()
            appendLine("package ${sourceName(packageName)}")
            appendLine()
            (IMPORTED_CLASSES + IMPORTED_FUNCTIONS).sorted().forEach { appendLine("import $it") }
            appendLine()
            appendLine("public class ${sourceName(className)} : PluginIndex {")
            appendLine("    override val info: PluginInfo = PluginInfo(")
            infoFields.forEach { (name, value) -> appendLine("        $name = ${kotlinValue(value)},") }
            appendLine("    )")
            appendLine()
            appendLine("    override val configRoot: String = ${literal(options.configRoot)}")
            appendLine()
            appendLine("    override fun bindings(): List<Binding<*>> = $CONTENTS.bindings()")
            appendLine()
            appendLine("    override fun configSections(): List<ConfigSectionSpec<*>> = $CONTENTS.configSections()")
            appendLine()
            appendLine("    private object $CONTENTS {")
            appendList("fun bindings(): List<Binding<*>>", bindings)
            appendLine()
            appendList("fun configSections(): List<ConfigSectionSpec<*>>", specs)
            appendLine("    }")
            appendLine("}")
        }
    }

    private fun descriptor(components: Collection<Component>): String {
        val names = components.sortedBy { it.origin }
            .flatMap { it.contributes }
            .mapNotNull { bound -> bound.name?.let { bound.className to jsonString(it) } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .entries
            .joinToString(prefix = "{", postfix = "}") { (spi, values) ->
                "${jsonString(spi)}: ${values.joinToString(prefix = "[", postfix = "]")}"
            }
        val fields = infoFields.map { (name, value) -> name to jsonValue(value) } + listOf(
            "contributionNames" to names,
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
            appendLine("        $declaration = emptyList()")
            return
        }
        appendLine("        $declaration = listOf(")
        elements.forEach { appendLine(it.prependIndent("            ") + ",") }
        appendLine("        )")
    }

    private fun sectionSpec(section: Section): String = buildString {
        appendLine("ConfigSectionSpec(")
        appendLine("    ${Key(section.type, null).code},")
        appendLine("    path = ${literal(section.path)},")
        appendLine("    deserializer = ${section.type}.serializer(),")
        appendLine("    origin = ${literal(section.origin)},")
        if (section.channelInstance) appendLine("    scope = Scope.CHANNEL_INSTANCE,")
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
            component.contributes.map { bound ->
                val flags = listOfNotNull("multi = true", bound.name?.let { "name = ${literal(it)}" })
                alias(component, bound.key, CONTRIBUTE, flags)
            }
    }

    private fun alias(component: Component, key: Key, annotation: String, flags: List<String> = emptyList()): String =
        binding(
            key,
            component,
            dependencies = listOf(
                dependencyCode(component.key, DependencyKind.INSTANCE, Messages.annotationLabel(annotation)),
            ),
            create = "{ $RESOLVER -> $RESOLVER.get(${component.key.code}) }",
            flags = flags + "managed = false",
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
        appendLine("    origin = ${literal(component.origin)},")
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

    private fun kotlinValue(value: Any): String = when (value) {
        is String -> literal(value)
        is List<*> -> listCode(value.map { literal(it as String) })
        else -> value.toString()
    }

    private fun jsonValue(value: Any): String = when (value) {
        is String -> jsonString(value)
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonString(it as String) }
        else -> value.toString()
    }

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

private const val CONTENTS = "Contents"

private val IMPORTED_CLASSES = listOf(
    OPT_IN,
    STRING,
    SUPPRESS,
    LIST,
    INTERNAL_API,
    CONFIG_SECTION_SPEC,
    BINDING,
    DEPENDENCY,
    DEPENDENCY_KIND,
    SCOPE,
    PLUGIN_INDEX,
    PLUGIN_INFO,
)

private val IMPORTED_FUNCTIONS = listOf(EMPTY_LIST, LIST_OF, BINDING_FUNCTION, KEY_FUNCTION)

/** The class names that the generated index of [indexClass] brings into scope, with what each stands for. */
internal fun generatedNames(indexClass: String): Map<String, String> = IMPORTED_CLASSES.associateBy(::simpleName) +
    (simpleName(indexClass) to "the index class itself") +
    (CONTENTS to "a nested object of the index class")

private fun simpleName(name: String): String = name.substringAfterLast('.')
