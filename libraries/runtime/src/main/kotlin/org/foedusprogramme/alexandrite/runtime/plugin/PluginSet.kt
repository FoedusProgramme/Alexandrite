package org.foedusprogramme.alexandrite.runtime.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import java.lang.reflect.InvocationTargetException
import java.net.URL

/** The plugin indexes a runtime loads. */
public class PluginSet private constructor(
    private val builtIns: List<BuiltInPlugin>,
    internal val members: List<Member>,
    /** Index classes on the class path that are neither built in nor added. */
    public val unlisted: List<String>,
    /** Index classes that more than one service file lists. */
    public val duplicates: List<String>,
    /** Built-in index classes that cannot be loaded. */
    internal val broken: List<Problem>,
) {
    public val plugins: List<LoadedPlugin> get() = members.map { it.plugin }

    public operator fun plus(index: PluginIndex): PluginSet = adding(index, duplicates)

    /** This set plus the plugin [id] found on [classLoader]. */
    public fun named(id: String, classLoader: ClassLoader = contextClassLoader()): PluginSet {
        val indexClass = describedIndexClass(id, classLoader)
        val index = loadIndex(indexClass, classLoader).getOrElse { cause ->
            throw IllegalArgumentException("Cannot load the index $indexClass of plugin '$id': $cause", cause)
        }
        require(index.info.id == id) {
            "The descriptor of plugin '$id' names the index $indexClass, which belongs to plugin '${index.info.id}'. " +
                "Rebuild the plugin."
        }
        return adding(index, (duplicates + duplicateIndexes(serviceFiles(classLoader))).distinct().sorted())
    }

    private fun adding(index: PluginIndex, duplicates: List<String>): PluginSet = PluginSet(
        builtIns,
        members + Member(index, builtIns.find { it.indexClass == index.javaClass.name }, explicit = true),
        unlisted - index.javaClass.name,
        duplicates,
        broken,
    )

    /** An index of the set and its row of the built-in list. */
    internal class Member(val index: PluginIndex, val row: BuiltInPlugin?, val explicit: Boolean) {
        val plugin: LoadedPlugin = LoadedPlugin(index.info, row?.layer, index.configRoot)
        val id: String get() = plugin.info.id
        val className: String get() = index.javaClass.name
    }

    public companion object {
        /** The built-in plugins of this build. */
        public val builtInPlugins: List<BuiltInPlugin> get() = BUILT_IN_PLUGINS

        /** The built-in plugins whose index is on the class path of [classLoader]. */
        public fun builtIn(classLoader: ClassLoader = contextClassLoader()): PluginSet =
            builtIn(classLoader, BUILT_IN_PLUGINS)

        public fun of(vararg indexes: PluginIndex): PluginSet = of(BUILT_IN_PLUGINS, *indexes)

        /** The plugins of [builtIns] whose index is on the class path of [classLoader]. */
        internal fun builtIn(classLoader: ClassLoader, builtIns: List<BuiltInPlugin>): PluginSet {
            val files = serviceFiles(classLoader)
            val names = files.flatten().distinct()
            val rows = builtIns.associateBy { it.indexClass }
            val members = mutableListOf<Member>()
            val broken = mutableListOf<Problem>()
            for (name in names) {
                val row = rows[name] ?: continue
                loadIndex(name, classLoader).fold(
                    { members += Member(it, row, explicit = false) },
                    { broken += brokenIndex(name, row, it) },
                )
            }
            return PluginSet(
                builtIns,
                members.sortedBy { it.id },
                names.filter { it !in rows }.sorted(),
                duplicateIndexes(files),
                broken,
            )
        }

        /** A set of [indexes] whose rows come from [builtIns]. */
        internal fun of(builtIns: List<BuiltInPlugin>, vararg indexes: PluginIndex): PluginSet =
            indexes.fold(PluginSet(builtIns, emptyList(), emptyList(), emptyList(), emptyList())) { set, index ->
                set + index
            }
    }
}

@Serializable
private class Descriptor(val indexClass: String)

private val descriptorJson = Json { ignoreUnknownKeys = true }

private fun describedIndexClass(id: String, classLoader: ClassLoader): String {
    require(PluginIds.PATTERN.matches(id)) { "Malformed plugin id '$id'." }
    val path = PluginIndex.descriptorPath(id)
    val files = resources(classLoader, path)
    require(files.isNotEmpty()) {
        "No plugin '$id' on the class path: no jar there holds $path. " +
            "Add the plugin's jar to the class path, or add its index with plus()."
    }
    require(files.size == 1) {
        "Several descriptors of plugin '$id' on the class path: ${files.joinToString()}. Keep only one of them."
    }
    val file = files.single()
    return try {
        descriptorJson.decodeFromString(Descriptor.serializer(), read(file)).indexClass
    } catch (e: SerializationException) {
        throw IllegalArgumentException("Malformed descriptor $file of plugin '$id': ${e.message}", e)
    }
}

private fun loadIndex(name: String, classLoader: ClassLoader): Result<PluginIndex> = try {
    val type = Class.forName(name, false, classLoader)
    if (PluginIndex::class.java.isAssignableFrom(type)) {
        Result.success(type.getDeclaredConstructor().newInstance() as PluginIndex)
    } else {
        Result.failure(ClassCastException("$name does not implement ${PluginIndex::class.java.name}"))
    }
} catch (e: InvocationTargetException) {
    Result.failure(e.targetException)
} catch (e: ReflectiveOperationException) {
    Result.failure(e)
} catch (e: RuntimeException) {
    Result.failure(e)
} catch (e: LinkageError) {
    Result.failure(e)
}

private fun brokenIndex(name: String, row: BuiltInPlugin, cause: Throwable): Problem = Problem(
    RuntimeProblemKind.BROKEN_INDEX,
    "Cannot load the built-in index $name of plugin '${row.id}': $cause. Rebuild the plugin's jar.",
    row.id,
    null,
)

private fun serviceFiles(classLoader: ClassLoader): List<List<String>> =
    resources(classLoader, PluginIndex.SERVICE_FILE).map { file ->
        read(file).lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
    }

private fun duplicateIndexes(files: List<List<String>>): List<String> = files
    .flatMap { it.distinct() }
    .groupingBy { it }
    .eachCount()
    .filterValues { it > 1 }
    .keys
    .sorted()

private fun resources(classLoader: ClassLoader, path: String): List<URL> =
    classLoader.getResources(path).toList().distinctBy { it.toExternalForm() }

private fun read(file: URL): String {
    val connection = file.openConnection().apply { useCaches = false }
    return connection.getInputStream().bufferedReader().use { it.readText() }
}

private fun contextClassLoader(): ClassLoader =
    Thread.currentThread().contextClassLoader ?: PluginSet::class.java.classLoader
