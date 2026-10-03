@file:OptIn(ExperimentalCompilerApi::class)

package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.di.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.instanceBinding
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.net.URLClassLoader
import java.util.Collections
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the compiled samples expose to the tests. */
interface Probe {
    fun report(): String
}

/** Collects what compiled samples record while the container creates them. */
class Recorder {
    private val events = Collections.synchronizedList(mutableListOf<String>())

    fun record(event: String) {
        events += event
    }

    fun all(): List<String> = synchronized(events) { events.toList() }
}

/** An error the processor reported, at [line] of [file] when it has a location. */
data class Reported(val file: String?, val line: Int?, val message: String)

/** The errors the processor reported in [messages]. */
fun reported(messages: String): Set<Reported> = messages.lines().mapNotNullTo(HashSet()) { line ->
    REPORTED.matchEntire(line)?.destructured?.let { (path, number, message) ->
        Reported(path.substringAfterLast('/').ifEmpty { null }, number.toIntOrNull(), message)
    }
}

private val REPORTED = Regex("""e: \[ksp] (?:(\S+\.kt):(\d+): )?(.*)""")

/** Compiles samples that must fail and compares their errors with the expected ones. */
abstract class FailingSamples {
    @TempDir
    lateinit var workingDir: File

    /** Asserts that `Sample.kt` made of [code] reports exactly [expected], each at the first line holding its marker. */
    fun assertErrors(code: String, vararg expected: Pair<String?, String>, entry: Boolean = true) {
        val sample = SAMPLE_HEADER + code.trimIndent()
        val lines = sample.lines()
        val located = expected.mapTo(HashSet()) { (marker, message) ->
            if (marker == null) {
                Reported(null, null, message)
            } else {
                val line = lines.indexOfFirst { marker in it } + 1
                assertTrue(line > 0, "no line with '$marker' in the sample")
                Reported("Sample.kt", line, message)
            }
        }
        val sources = listOfNotNull(source("Sample.kt", sample), ENTRY.takeIf { entry })
        compile(workingDir, *sources.toTypedArray()).use { compiled ->
            assertFalse(compiled.succeeded, "the compilation should fail")
            assertEquals(located, reported(compiled.messages), compiled.messages)
        }
    }

    private companion object {
        val SAMPLE_HEADER = """
            package sample

            import kotlinx.serialization.Serializable
            import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
            import org.foedusprogramme.alexandrite.sdk.di.*
            import org.foedusprogramme.alexandrite.sdk.hook.*
            import org.foedusprogramme.alexandrite.sdk.plugin.*
            import org.foedusprogramme.alexandrite.sdk.runtime.*
            import org.foedusprogramme.alexandrite.sdk.tool.*

            class Clock

        """.trimIndent()

        val ENTRY = entry("sample")
    }
}

class Compiled(private val compilation: KotlinCompilation, private val result: JvmCompilationResult) : AutoCloseable {
    val succeeded: Boolean get() = result.exitCode == KotlinCompilation.ExitCode.OK
    val messages: String get() = result.messages

    private val resources = compilation.kspSourcesDir.resolve("resources")

    private val loader = lazy {
        URLClassLoader(
            arrayOf(result.outputDirectory.toURI().toURL(), resources.toURI().toURL()),
            Compiled::class.java.classLoader,
        )
    }

    val classLoader: ClassLoader get() = loader.value

    fun indexes(): List<PluginIndex> = ServiceLoader.load(PluginIndex::class.java, classLoader).toList()

    fun service(): String = resources.resolve("META-INF/services/${PluginIndex::class.java.name}").readText()

    fun descriptorText(id: String): String = resources.resolve("META-INF/alexandrite/$id.json").readText()

    fun descriptor(id: String): JsonObject = Json.parseToJsonElement(descriptorText(id)).jsonObject

    fun assertSucceeded() = assertTrue(succeeded, messages)

    override fun close() {
        if (loader.isInitialized()) loader.value.close()
    }
}

fun sampleOptions(id: String = "sample", vararg extra: Pair<String, String>): Map<String, String> =
    mapOf(PLUGIN_OPTION to id, VERSION_OPTION to "1.0.0", *extra)

fun compile(
    workingDir: File,
    vararg sources: SourceFile,
    options: Map<String, String> = sampleOptions(),
    explicitApi: Boolean = false,
    extraProcessor: SymbolProcessorProvider? = null,
): Compiled {
    workingDir.mkdirs()
    val compilation = KotlinCompilation().apply {
        this.workingDir = workingDir
        this.sources = sources.toList()
        inheritClassPath = true
        jvmTarget = "21"
        allWarningsAsErrors = true
        messageOutputStream = OutputStream.nullOutputStream()
        compilerPluginRegistrars = listOf(SerializationComponentRegistrar())
        if (explicitApi) kotlincArguments = listOf("-Xexplicit-api=strict")
        configureKsp {
            symbolProcessorProviders += AlexandriteProcessorProvider()
            extraProcessor?.let(symbolProcessorProviders::add)
            processorOptions += options
        }
    }
    return Compiled(compilation, compilation.compile())
}

fun source(name: String, contents: String): SourceFile = SourceFile.kotlin(name, contents)

/** A source file of the `@Plugin` class [name] in [packageName]. */
fun entry(packageName: String, name: String = "SamplePlugin"): SourceFile =
    source("$name.kt", "package $packageName\n\n@$PLUGIN(name = \"Sample\")\nclass $name\n")

/** [origin] without the package [packageName]. */
fun simpleOrigin(origin: String, packageName: String): String = origin.removePrefix("$packageName.")

/** [binding] as `key scope flags <- dependencies`. */
fun describe(binding: Binding<*>): String {
    val flags = listOfNotNull("multi".takeIf { binding.multi }, "unmanaged".takeIf { !binding.managed })
    val dependencies = binding.dependencies.joinToString { "${it.site}: ${it.kind} ${it.key}" }
    return (listOf(binding.key.toString(), binding.scope.toString()) + flags).joinToString(" ") +
        " <- " + dependencies
}

fun PluginIndex.pluginBindings(): PluginBindings = PluginBindings(info.id, bindings())

/** The sections of [index], each decoded leniently from its subtree of [config]. */
fun sectionBindings(index: PluginIndex, config: JsonObject): List<Binding<*>> =
    index.configSections().map { it.decode(index, config) }

private fun <T : Any> ConfigSectionSpec<T>.decode(index: PluginIndex, config: JsonObject): Binding<T> {
    val path = (index.configRoot.split('.') + path.split('.').filter { it.isNotEmpty() })
    val tree = path.fold<String, JsonObject?>(config) { node, name -> node?.get(name)?.jsonObject }
    val value = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(deserializer, tree ?: JsonObject(emptyMap()))
    return instanceBinding(key, value, index.info.id, origin)
}

/** Generates [contents] as `sample/[name].kt` in the first round. */
class GeneratingProvider(private val name: String, private val contents: String) : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
        private var generated = false

        override fun process(resolver: Resolver): List<KSAnnotated> {
            if (!generated) {
                generated = true
                environment.codeGenerator.createNewFile(Dependencies(aggregating = false), "sample", name)
                    .writer()
                    .use { it.write(contents) }
            }
            return emptyList()
        }
    }
}
