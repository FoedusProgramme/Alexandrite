package org.foedusprogramme.alexandrite.buildlogic

enum class Layer {
    /** The public SDK plugin authors compile against. */
    SDK,

    /** Internal shared utilities. */
    COMMON,

    /** The turn pipeline, permissions, store and automation. */
    AGENT,

    /** Built-in tools. */
    TOOLS,

    /** Front ends. */
    CHANNEL,

    /** Model back ends. */
    PROVIDER,

    /** KSP symbol processor. */
    KSP,

    /** Entry executable. */
    APP,
}

sealed class Location {
    abstract val directory: String
    abstract val layer: Layer

    data class Slot(
        override val directory: String,
        override val layer: Layer,
        val jarName: String = "alexandrite-" + directory.substringAfterLast('/'),
        val configRoot: String? = null,
    ) : Location()

    data class Family(
        override val directory: String,
        override val layer: Layer,
        val jarPrefix: String,
        val configRootPrefix: String = directory.substringAfterLast('/') + ".",
    ) : Location()
}

data class AlexandriteModule(val path: String, val layer: Layer, val jarName: String, val configRoot: String?)

data class Discovery(
    val modules: List<AlexandriteModule>,
    val misplaced: List<String>,
    val missingSlots: List<Location.Slot>,
) {
    /** Null when the layout holds, otherwise the message settings evaluation fails with. */
    val failure: String?
        get() = AlexandriteLayout.placementFailure(misplaced, missingSlots)
}

interface ModuleTree {
    /** Whether [directory] holds a `build.gradle.kts` file */
    fun hasBuildFile(directory: String): Boolean

    /** The names of [directory]'s direct subdirectories */
    fun subdirectories(directory: String): List<String>
}

object AlexandriteLayout {
    /** Where the layout lives */
    const val LAYOUT_LOCATION =
        "build-logic-settings/src/main/kotlin/org/foedusprogramme/alexandrite/buildlogic/AlexandriteLayout.kt"

    /** Configurations whose name starts with this may depend on a [Layer.KSP] project */
    const val KSP_CONFIGURATION_PREFIX = "ksp"

    /** Configurations whose name starts with this may also depend on the layers of [TEST_LAYER_DEPENDENCIES] */
    const val TEST_CONFIGURATION_PREFIX = "test"

    /** The file that makes a directory a module */
    const val BUILD_FILE = "build.gradle.kts"

    /** The tree [scan] walks for misplaced modules */
    const val LIBRARIES = "libraries"

    /** Location Map */
    val LOCATIONS: List<Location> = listOf(
        Location.Slot("libraries/plugin-sdk", Layer.SDK),
        Location.Slot("libraries/common", Layer.COMMON),
        Location.Slot("libraries/agent", Layer.AGENT, configRoot = "agent"),
        Location.Slot("libraries/tools", Layer.TOOLS, configRoot = "tools"),
        Location.Family("libraries/channels", Layer.CHANNEL, jarPrefix = "alexandrite-channel-"),
        Location.Family("libraries/providers", Layer.PROVIDER, jarPrefix = "alexandrite-provider-"),
        Location.Slot("build-ksp-plugin", Layer.KSP, jarName = "alexandrite-ksp"),
        Location.Slot("app", Layer.APP, jarName = "alexandrite"),
    )

    val LAYER_DEPENDENCIES: Map<Layer, Set<Layer>> = mapOf(
        Layer.SDK to emptySet(),
        Layer.COMMON to emptySet(),
        Layer.KSP to emptySet(),
        Layer.AGENT to setOf(Layer.SDK, Layer.COMMON),
        Layer.TOOLS to setOf(Layer.SDK, Layer.COMMON),
        Layer.CHANNEL to setOf(Layer.SDK, Layer.COMMON),
        Layer.PROVIDER to setOf(Layer.SDK, Layer.COMMON),
        Layer.APP to setOf(Layer.SDK, Layer.COMMON, Layer.AGENT, Layer.TOOLS, Layer.CHANNEL, Layer.PROVIDER),
    )

    val TEST_LAYER_DEPENDENCIES: Map<Layer, Set<Layer>> = mapOf(Layer.KSP to setOf(Layer.SDK))

    /** Layers whose modules get a generated `ModuleIndex` */
    val INDEXED_LAYERS: Set<Layer> = setOf(Layer.AGENT, Layer.TOOLS, Layer.CHANNEL, Layer.PROVIDER)

    private val slots: List<Location.Slot> = LOCATIONS.filterIsInstance<Location.Slot>()

    fun moduleAt(path: String): AlexandriteModule? =
        if (path.startsWith(":") && path.length > 1) moduleIn(path.substring(1).replace(':', '/')) else null

    fun moduleIn(directory: String): AlexandriteModule? {
        val path = ":" + directory.replace('/', ':')
        for (location in LOCATIONS) {
            when (location) {
                is Location.Slot -> {
                    if (directory == location.directory) {
                        return AlexandriteModule(path, location.layer, location.jarName, location.configRoot)
                    }
                }

                is Location.Family -> {
                    val name = directory.removePrefix(location.directory + "/")
                    if (name != directory && name.isNotEmpty() && '/' !in name) {
                        return AlexandriteModule(
                            path,
                            location.layer,
                            location.jarPrefix + name,
                            location.configRootPrefix + name,
                        )
                    }
                }
            }
        }
        return null
    }

    fun scan(tree: ModuleTree): List<String> {
        val found = sortedSetOf<String>()
        slots.filter { tree.hasBuildFile(it.directory) }.mapTo(found) { it.directory }
        fun walk(directory: String) {
            if (tree.hasBuildFile(directory)) {
                found += directory
                if (moduleIn(directory) != null) return
            }
            for (name in tree.subdirectories(directory)) {
                if (name != "build" && !name.startsWith(".")) walk("$directory/$name")
            }
        }
        walk(LIBRARIES)
        return found.toList()
    }

    fun discover(buildFileDirectories: Collection<String>): Discovery {
        val present = buildFileDirectories.toSortedSet()
        return Discovery(
            modules = present.mapNotNull(::moduleIn).sortedBy { it.path },
            misplaced = present.filter { moduleIn(it) == null },
            missingSlots = slots.filter { it.directory !in present },
        )
    }

    fun placementFailure(misplaced: List<String>, missingSlots: List<Location.Slot>): String? {
        if (misplaced.isEmpty() && missingSlots.isEmpty()) return null
        val problems = misplaced.map { "$it/ holds $BUILD_FILE but is no module location." } +
            missingSlots.map { "${it.directory}/ is the ${it.layer} slot but holds no $BUILD_FILE." }
        return "Modules are out of place:\n" +
            problems.joinToString("\n") { "  - $it" } + "\n" +
            "Modules may live only at:\n" + locationList() + "\n" +
            "Every slot must hold its module; a channel or provider is added by creating its directory. " +
            "A new kind of module needs a new slot or family in the location map, with its layer's dependency " +
            "rules, in $LAYOUT_LOCATION."
    }

    fun noLocationMessage(path: String): String =
        "Project '$path' is at no location of the layout, so it has no layer. Modules may live only at:\n" +
            locationList() + "\n" +
            "See the location map in $LAYOUT_LOCATION."

    /**
     * Null when [module] may declare a dependency on the project at [dependencyPath] in its configuration
     * [configurationName].
     */
    fun dependencyViolation(module: AlexandriteModule, dependencyPath: String, configurationName: String): String? {
        if (dependencyPath == module.path) return null
        val testLayers = TEST_LAYER_DEPENDENCIES[module.layer].orEmpty()
        val dependency = moduleAt(dependencyPath)
        if (dependency != null) {
            if (dependency.layer in LAYER_DEPENDENCIES.getValue(module.layer)) return null
            if (dependency.layer == Layer.KSP && configurationName.startsWith(KSP_CONFIGURATION_PREFIX)) return null
            if (dependency.layer in testLayers && configurationName.startsWith(TEST_CONFIGURATION_PREFIX)) return null
        }
        val allowed = pathPatterns(LAYER_DEPENDENCIES.getValue(module.layer)).ifEmpty { "none" }
        val testOnly = if (testLayers.isEmpty()) {
            ""
        } else {
            "${pathPatterns(testLayers)} only from configurations named $TEST_CONFIGURATION_PREFIX*; "
        }
        return "Forbidden project dependency: '${module.path}' (${module.layer}) may not depend on '$dependencyPath' " +
            "(${dependency?.layer ?: "at no location of the layout"}) in configuration '$configurationName'. " +
            "Allowed project dependencies of '${module.path}': $allowed; " + testOnly +
            "${pathPatterns(setOf(Layer.KSP))} only from configurations named $KSP_CONFIGURATION_PREFIX*. " +
            "See the layout in $LAYOUT_LOCATION."
    }

    private fun pathPatterns(layers: Set<Layer>): String =
        LOCATIONS.filter { it.layer in layers }.joinToString { pathPattern(it) }

    /** `:libraries:agent` for a slot, `:libraries:providers:*` for a family. */
    private fun pathPattern(location: Location): String {
        val path = ":" + location.directory.replace('/', ':')
        return if (location is Location.Family) "$path:*" else path
    }

    private fun locationList(): String = LOCATIONS.joinToString("\n") { location ->
        val directory = if (location is Location.Family) "${location.directory}/<name>/" else "${location.directory}/"
        "  $directory (${location.layer})"
    }
}
