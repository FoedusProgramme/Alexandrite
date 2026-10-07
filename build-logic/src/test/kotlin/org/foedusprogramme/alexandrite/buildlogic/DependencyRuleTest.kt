package org.foedusprogramme.alexandrite.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class DependencyRuleTest {
    @TempDir
    lateinit var root: File

    /** A build of the modules in [dependencies], keyed by directory, that runs `help`. */
    private fun build(dependencies: Map<String, String>): GradleRunner {
        val catalog = File("../gradle/libs.versions.toml").absoluteFile.invariantSeparatorsPath
        val includes = dependencies.keys.joinToString { "\":${it.replace('/', ':')}\"" }
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                versionCatalogs {
                    create("libs") { from(files("$catalog")) }
                }
            }

            include($includes)
            """.trimIndent(),
        )
        for ((directory, declared) in dependencies) {
            root.resolve(directory).mkdirs()
            root.resolve("$directory/build.gradle.kts").writeText(
                "plugins {\n    id(\"alexandrite.kotlin-library\")\n}\n\ndependencies {\n    $declared\n}\n",
            )
        }
        return GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments("help")
    }

    @Test
    fun `a forbidden project dependency fails the build`() {
        val result = build(
            mapOf(
                "libraries/plugin-sdk" to "implementation(project(\":libraries:internal\"))",
                "libraries/internal" to "",
            ),
        ).buildAndFail()

        assertContains(
            result.output,
            "Forbidden project dependency: ':libraries:plugin-sdk' (SDK) may not depend on ':libraries:internal' " +
                "(INTERNAL) in configuration 'implementation'.",
        )
    }

    @Test
    fun `allowed project dependencies, test ones included, build`() {
        build(
            mapOf(
                "libraries/plugin-sdk" to "",
                "libraries/internal" to "",
                "libraries/runtime" to "implementation(project(\":libraries:plugin-sdk\"))",
                "libraries/testkit" to "api(project(\":libraries:runtime\"))",
                "examples/echo" to "compileOnly(project(\":libraries:plugin-sdk\"))\n" +
                    "    testImplementation(project(\":libraries:testkit\"))",
            ),
        ).build()
    }
}
