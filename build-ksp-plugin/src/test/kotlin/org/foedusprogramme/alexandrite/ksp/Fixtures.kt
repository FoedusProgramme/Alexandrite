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
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar
import java.io.File
import java.io.OutputStream
import java.net.URLClassLoader
import java.util.ServiceLoader
import kotlin.test.assertTrue

/** What the compiled samples expose to the tests. */
interface Probe {
    fun report(): String
}

class Compiled(private val compilation: KotlinCompilation, private val result: JvmCompilationResult) {
    val succeeded: Boolean get() = result.exitCode == KotlinCompilation.ExitCode.OK
    val messages: String get() = result.messages

    private val resources = compilation.kspSourcesDir.resolve("resources")

    val classLoader: ClassLoader = URLClassLoader(
        arrayOf(result.outputDirectory.toURI().toURL(), resources.toURI().toURL()),
        Compiled::class.java.classLoader,
    )

    fun indexes(): List<ModuleIndex> = ServiceLoader.load(ModuleIndex::class.java, classLoader).toList()

    fun service(): String = resources.resolve("META-INF/services/${ModuleIndex::class.java.name}").readText()

    fun generated(qualifiedName: String): String =
        compilation.kspSourcesDir.resolve("kotlin/${qualifiedName.replace('.', '/')}.kt").readText()

    fun assertSucceeded() = assertTrue(succeeded, messages)
}

fun compile(
    workingDir: File,
    vararg sources: SourceFile,
    options: Map<String, String> = mapOf(MODULE_OPTION to "sample"),
    explicitApi: Boolean = false,
    extraProcessor: SymbolProcessorProvider? = null,
): Compiled {
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

/** Generates [contents] as `sample/Generated.kt` in the first round. */
class GeneratingProvider(private val contents: String) : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
        private var generated = false

        override fun process(resolver: Resolver): List<KSAnnotated> {
            if (!generated) {
                generated = true
                environment.codeGenerator.createNewFile(Dependencies(aggregating = false), "sample", "Generated")
                    .writer()
                    .use { it.write(contents) }
            }
            return emptyList()
        }
    }
}
