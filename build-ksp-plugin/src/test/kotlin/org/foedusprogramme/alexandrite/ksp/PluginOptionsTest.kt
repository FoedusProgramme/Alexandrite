package org.foedusprogramme.alexandrite.ksp

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PluginOptionsTest {
    @TempDir
    lateinit var workingDir: File

    private val required = arrayOf(PLUGIN_OPTION to "weather", VERSION_OPTION to "1.2.3")

    private val builtIn = arrayOf(
        PLUGIN_OPTION to "alexandrite-weather",
        VERSION_OPTION to "1.2.3",
        CONFIG_ROOT_OPTION to "weather",
        BUILT_IN_OPTION to "true",
    )

    private fun options(vararg options: Pair<String, String>): PluginOptions {
        val read = pluginOptions(mapOf(*options))
        assertEquals(emptyList(), read.problems.map { it.message })
        return assertNotNull(read.value)
    }

    private fun assertProblems(options: Array<out Pair<String, String>>, vararg expected: String) {
        val read = pluginOptions(mapOf(*options))
        assertEquals(expected.toList(), read.problems.map { it.message }, options.toList().toString())
        assertNull(read.value)
    }

    private fun component(packageName: String, name: String): SourceFile =
        source("$name.kt", "package $packageName\n\n@$SINGLETON\nclass $name\n")

    // Plugin id and version.

    @Test
    fun `the plugin id and the version are required`() {
        assertProblems(arrayOf(VERSION_OPTION to "1.2.3"), Messages.missingPlugin())
        assertProblems(arrayOf(PLUGIN_OPTION to "weather"), Messages.missingVersion())
        assertProblems(arrayOf(PLUGIN_OPTION to "weather", VERSION_OPTION to " "), Messages.missingVersion())
    }

    @Test
    fun `a plugin id outside the grammar is rejected`() {
        val ids =
            listOf("my_plugin", "MyPlugin", "my--plugin", "-plugin", "plugin-", "1plugin", "my plugin", "", "a-1b")
        for (id in ids) {
            assertProblems(arrayOf(PLUGIN_OPTION to id, VERSION_OPTION to "1"), Messages.malformedPlugin(id))
        }
    }

    @Test
    fun `the options of a third-party plugin default its config root`() {
        val options = options(*required)

        assertEquals("weather", options.id)
        assertEquals("1.2.3", options.version)
        assertEquals("plugins.weather", options.configRoot)
        assertFalse(options.builtIn)
        assertNull(options.packageName)
        assertNull(options.indexClass)
    }

    @Test
    fun `unknown alexandrite options are rejected and other processors' options ignored`() {
        assertProblems(
            arrayOf(*required, "alexandrite.module" to "weather", "alexandrite.packge" to "x", "room.x" to "y"),
            Messages.unknownOptions(listOf("alexandrite.module", "alexandrite.packge"), OPTIONS),
        )
    }

    // Built-in plugins.

    @Test
    fun `a built-in plugin keeps its reserved id and config root`() {
        val options = options(*builtIn)

        assertEquals("alexandrite-weather", options.id)
        assertEquals("weather", options.configRoot)
        assertEquals(true, options.builtIn)
    }

    @Test
    fun `a reserved id needs the built-in option`() {
        val reserved = arrayOf(PLUGIN_OPTION to "alexandrite-weather", VERSION_OPTION to "1", CONFIG_ROOT_OPTION to "w")
        assertProblems(reserved, Messages.reservedPlugin("alexandrite-weather"))
        assertProblems(arrayOf(*reserved, BUILT_IN_OPTION to "false"), Messages.reservedPlugin("alexandrite-weather"))
        assertProblems(
            arrayOf(*reserved, BUILT_IN_OPTION to "yes"),
            Messages.malformedBuiltIn("yes"),
            Messages.reservedPlugin("alexandrite-weather"),
        )
    }

    @Test
    fun `the built-in option is rejected on an id that is not reserved`() {
        assertProblems(arrayOf(*required, BUILT_IN_OPTION to "true"), Messages.builtInThirdParty("weather"))
    }

    @Test
    fun `a built-in plugin needs a config root in the path grammar`() {
        val withoutRoot = builtIn.filter { it.first != CONFIG_ROOT_OPTION }.toTypedArray()
        assertProblems(withoutRoot, Messages.missingConfigRoot("alexandrite-weather"))
        for (configRoot in listOf("", "channels..x", ".x", "x.", "1x", "my root")) {
            assertProblems(
                arrayOf(*withoutRoot, CONFIG_ROOT_OPTION to configRoot),
                Messages.malformedConfigRoot(configRoot),
            )
        }
    }

    @Test
    fun `a third-party plugin cannot choose another config root`() {
        assertEquals("plugins.weather", options(*required, CONFIG_ROOT_OPTION to "plugins.weather").configRoot)
        for (configRoot in listOf("channels.weather", "plugins", "plugins.other", "tools")) {
            assertProblems(
                arrayOf(*required, CONFIG_ROOT_OPTION to configRoot),
                Messages.thirdPartyRoot("weather", configRoot),
            )
        }
    }

    // Package and index class.

    @Test
    fun `a malformed package or index class is rejected`() {
        for (packageName in listOf("", "com..example", "com.example.", "1com.example", "com.my-plugin")) {
            assertProblems(arrayOf(*required, PACKAGE_OPTION to packageName), Messages.malformedPackage(packageName))
        }
        for (indexClass in listOf("WeatherIndex", "com..WeatherIndex", "com.example.", "com.1Index")) {
            assertProblems(
                arrayOf(*required, INDEX_CLASS_OPTION to indexClass),
                Messages.malformedIndexClass(indexClass),
            )
        }
        assertProblems(
            arrayOf(*required, PACKAGE_OPTION to "com.example", INDEX_CLASS_OPTION to "org.example.WeatherIndex"),
            Messages.indexClassOutsidePackage("org.example.WeatherIndex", "com.example"),
        )
    }

    @Test
    fun `the index class is the option, else the plugin's index name in the package option or the inferred one`() {
        val verbatim = options(*required, INDEX_CLASS_OPTION to "com.example.Custom")
        val placed = options(*required, PACKAGE_OPTION to "com.example.weather")
        val inferred = options(*required)

        assertEquals("com.example.Custom", verbatim.indexClassFor("org.acme"))
        assertEquals("com.example.weather.WeatherIndex", placed.indexClassFor("org.acme"))
        assertEquals("org.acme.WeatherIndex", inferred.indexClassFor("org.acme"))
        assertNull(inferred.indexClassFor(null))
    }

    @Test
    fun `index class names follow the golden table shared with the layout`() {
        val table = javaClass.getResource("/plugin-index-names.txt")!!.readBytes()
        assertContentEquals(
            table,
            File("../build-logic-settings/src/test/resources/plugin-index-names.txt").readBytes(),
        )

        for ((id, name) in table.decodeToString().lines().filter { it.isNotBlank() }.map { it.split(' ') }) {
            assertEquals(true, PLUGIN_ID.matches(id), id)
            assertEquals(name, indexClassName(id), id)
        }
    }

    @Test
    fun `string literals follow the golden table shared with the layout`() {
        val table = javaClass.getResource("/kotlin-string-literals.txt")!!.readBytes()
        assertContentEquals(
            table,
            File("../build-logic-settings/src/test/resources/kotlin-string-literals.txt").readBytes(),
        )

        for (line in table.decodeToString().lines().filter { it.isNotBlank() }) {
            assertEquals(line.substringAfter(' '), literal(line.substringBefore(' ')), line)
        }
    }

    @Test
    fun `the inferred package is the longest common package`() {
        assertEquals("org.acme.weather", commonPackage(listOf("org.acme.weather", "org.acme.weather.tools.x")))
        assertEquals("org.acme", commonPackage(listOf("org.acme.weather.tools", "org.acme.weatherman")))
        assertEquals("org.acme.weather", commonPackage(listOf("org.acme.weather")))
        assertNull(commonPackage(listOf("alpha", "beta")))
        assertNull(commonPackage(listOf("", "alpha")))
        assertNull(commonPackage(emptyList()))
    }

    // Compiled.

    @Test
    fun `an unusable option fails the compilation with how to set it`() {
        compile(workingDir, entry("sample"), options = mapOf(PLUGIN_OPTION to "weather")).use { compiled ->
            assertFalse(compiled.succeeded)
            assertEquals(setOf(Reported(null, null, Messages.missingVersion())), reported(compiled.messages))
        }
    }

    @Test
    fun `the index class option names the index, its service entry and its descriptor`() {
        val options = sampleOptions(
            "weather",
            PACKAGE_OPTION to "com.example",
            INDEX_CLASS_OPTION to "com.example.Custom",
        )
        compile(workingDir, entry("sample"), options = options).use { compiled ->
            compiled.assertSucceeded()

            assertEquals("com.example.Custom", compiled.indexes().single().javaClass.name)
            assertEquals("com.example.Custom\n", compiled.service())
            assertEquals("\"com.example.Custom\"", compiled.descriptor("weather")["indexClass"].toString())
        }
    }

    @Test
    fun `without options the index goes to the longest common package of the annotated classes`() {
        val sources = listOf(entry("org.acme.weather"), component("org.acme.weather.tools", "Tools")) +
            source("Unannotated.kt", "package org\n\nclass Unannotated\n")
        compile(workingDir, *sources.toTypedArray(), options = sampleOptions("weather")).use { compiled ->
            compiled.assertSucceeded()

            assertEquals("org.acme.weather.WeatherIndex", compiled.indexes().single().javaClass.name)
        }
    }

    @Test
    fun `a plugin whose package cannot be inferred fails with how to set it`() {
        val sources = arrayOf(entry("alpha"), component("beta", "B"))
        compile(workingDir, *sources, options = sampleOptions("my-weather")).use { compiled ->
            assertFalse(compiled.succeeded)
            assertEquals(
                setOf(Reported(null, null, Messages.missingPackage("my-weather"))),
                reported(compiled.messages),
            )
        }
    }
}
