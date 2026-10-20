package org.unitedsoftware.alexandrite.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlexandritePluginTest {
    private fun project(): Project = ProjectBuilder.builder().build().also {
        it.pluginManager.apply("org.unitedsoftware.alexandrite.plugin")
    }

    private fun Project.declared(configuration: String): List<String> =
        configurations.getByName(configuration).dependencies.withType(ExternalDependency::class.java)
            .map { listOfNotNull(it.group, it.name, it.version).joinToString(":") }

    private val Project.alexandrite: AlexandriteExtension
        get() = extensions.getByType(AlexandriteExtension::class.java)

    @Test
    fun `the plugin applies Kotlin and KSP`() {
        val project = project()

        assertTrue(project.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm"))
        assertTrue(project.pluginManager.hasPlugin("com.google.devtools.ksp"))
    }

    @Test
    fun `the SDK, the processor and the testkit come in their configurations, aligned by the BOM of this version`() {
        val project = project()
        val bom = "org.unitedsoftware.alexandrite:alexandrite-bom:$ALEXANDRITE_VERSION"

        assertEquals(
            listOf(bom, "org.unitedsoftware.alexandrite:alexandrite-plugin-sdk"),
            project.declared("compileOnly"),
        )
        assertEquals(listOf(bom, "org.unitedsoftware.alexandrite:alexandrite-ksp"), project.declared("ksp"))
        assertEquals(
            listOf(bom, "org.unitedsoftware.alexandrite:alexandrite-testkit"),
            project.declared("testImplementation"),
        )
    }

    @Test
    fun `the KSP options carry the id and the project version`() {
        val project = project()
        project.version = "1.2.0"
        project.alexandrite.pluginId.set("notes")

        assertEquals(
            listOf("alexandrite.plugin=notes", "alexandrite.version=1.2.0"),
            KspOptions(project.alexandrite).asArguments(),
        )
    }

    @Test
    fun `the KSP options carry the version, package and index class an author sets`() {
        val project = project()
        project.alexandrite.apply {
            pluginId.set("notes")
            pluginVersion.set("2.0.0")
            indexPackage.set("com.example.notes")
            indexClass.set("com.example.notes.NotesIndex")
        }

        assertEquals(
            listOf(
                "alexandrite.plugin=notes",
                "alexandrite.version=2.0.0",
                "alexandrite.package=com.example.notes",
                "alexandrite.indexClass=com.example.notes.NotesIndex",
            ),
            KspOptions(project.alexandrite).asArguments(),
        )
    }

    @Test
    fun `a plugin without an id fails with the line to add`() {
        val failure = assertFailsWith<GradleException> { KspOptions(project().alexandrite).asArguments() }

        assertContains(failure.message.orEmpty(), "alexandrite { pluginId = \"my-plugin\" }")
    }

    @Test
    fun `classes target the host's Java release whatever JDK builds them`() {
        val project = project()

        val kotlin = project.tasks.withType(KotlinJvmCompile::class.java).getByName("compileKotlin")
        val java = project.tasks.withType(JavaCompile::class.java).getByName("compileJava")
        assertEquals(JvmTarget.JVM_21, kotlin.compilerOptions.jvmTarget.get())
        assertEquals(21, java.options.release.get())
    }
}
