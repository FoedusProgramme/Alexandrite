package org.foedusprogramme.alexandrite.runtime

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import java.lang.reflect.InvocationTargetException
import java.net.URL

/** The plugin indexes a runtime loads. */
public class PluginSet private constructor(
    internal val entries: List<PluginEntry>,
    /** Index classes on the class path that are neither built in nor added. */
    public val unlisted: List<String>,
    /** Index classes that more than one service file lists. */
    public val duplicates: List<String>,
    /** Built-in index classes that cannot be loaded. */
    internal val broken: List<Problem>,
) {
    public val plugins: List<LoadedPlugin> get() = entries.map { it.plugin }

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
        entries + PluginEntry(index, BuiltIns.row(index.javaClass.name), explicit = true),
        unlisted - index.javaClass.name,
        duplicates,
        broken,
    )

    public companion object {
        private val EMPTY = PluginSet(emptyList(), emptyList(), emptyList(), emptyList())

        /** The built-in plugins of this build. */
        public val builtInPlugins: List<BuiltInPlugin> get() = BuiltIns.rows

        /** The built-in plugins whose index is on the class path of [classLoader]. */
        public fun builtIn(classLoader: ClassLoader = contextClassLoader()): PluginSet {
            val files = serviceFiles(classLoader)
            val names = files.flatten().distinct()
            val rows = BuiltIns.rows.associateBy { it.indexClass }
            val entries = mutableListOf<PluginEntry>()
            val broken = mutableListOf<Problem>()
            for (name in names) {
                val row = rows[name] ?: continue
                loadIndex(name, classLoader).fold(
                    { entries += PluginEntry(it, row, explicit = false) },
                    { broken += brokenIndex(name, row, it) },
                )
            }
            return PluginSet(
                entries.sortedBy { it.id },
                names.filter { it !in rows }.sorted(),
                duplicateIndexes(files),
                broken,
            )
        }

        public fun of(vararg indexes: PluginIndex): PluginSet = indexes.fold(EMPTY) { set, index -> set + index }
    }
}

/** A plugin of a [PluginSet]. */
@Poko
public class LoadedPlugin internal constructor(
    public val info: PluginInfo,
    /** The layer of a built-in plugin, null for any other. */
    public val layer: BuiltInLayer?,
    public val configRoot: String,
) {
    public val builtIn: Boolean get() = layer != null

    override fun toString(): String = "LoadedPlugin(id=${info.id}, layer=$layer, configRoot=$configRoot)"
}

/** A plugin on the built-in list compiled into the runtime. */
@Poko
public class BuiltInPlugin internal constructor(
    public val indexClass: String,
    public val id: String,
    public val layer: BuiltInLayer,
    public val configRoot: String,
)

internal class PluginEntry(val index: PluginIndex, val row: BuiltInPlugin?, val explicit: Boolean) {
    val plugin: LoadedPlugin = LoadedPlugin(index.info, row?.layer, index.configRoot)
    val id: String get() = plugin.info.id
    val className: String get() = index.javaClass.name
}

/** The built-in list every [PluginSet] reads. */
internal object BuiltIns {
    @Volatile
    var rows: List<BuiltInPlugin> = BUILT_IN_PLUGINS

    fun row(indexClass: String): BuiltInPlugin? = rows.find { it.indexClass == indexClass }
}

@Serializable
private class Descriptor(val indexClass: String)

private val descriptorJson = Json { ignoreUnknownKeys = true }

private const val DESCRIPTOR_DIRECTORY = "META-INF/alexandrite"

private val SERVICE_FILE = "META-INF/services/" + PluginIndex::class.java.name

private fun describedIndexClass(id: String, classLoader: ClassLoader): String {
    require(PluginIds.PATTERN.matches(id)) { "Malformed plugin id '$id'." }
    val path = "$DESCRIPTOR_DIRECTORY/$id.json"
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

/** The class names each service file on [classLoader] lists. */
private fun serviceFiles(classLoader: ClassLoader): List<List<String>> =
    resources(classLoader, SERVICE_FILE).map { file ->
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
