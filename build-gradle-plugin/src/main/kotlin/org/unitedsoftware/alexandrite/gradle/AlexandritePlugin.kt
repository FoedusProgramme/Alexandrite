package org.unitedsoftware.alexandrite.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Plugin `org.unitedsoftware.alexandrite.plugin`: builds a third-party Alexandrite plugin with Kotlin and the
 * Alexandrite KSP processor, against the SDK of its own version.
 */
public class AlexandritePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.pluginManager.apply("com.google.devtools.ksp")

        val extension = project.extensions.create("alexandrite", AlexandriteExtension::class.java)
        extension.pluginVersion.convention(project.provider { project.version.toString() })
        project.extensions.getByType(KspExtension::class.java).arg(KspOptions(extension))

        // The host runs on Java 21, so a plugin built with a newer JDK must still emit classes it can load.
        project.tasks.withType(KotlinJvmCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.fromTarget(JAVA_RELEASE.toString()))
        }
        project.tasks.withType(JavaCompile::class.java).configureEach { options.release.set(JAVA_RELEASE) }

        val dependencies = project.dependencies
        for (configuration in listOf("compileOnly", "ksp", "testImplementation")) {
            dependencies.add(configuration, dependencies.platform("$GROUP:alexandrite-bom:$ALEXANDRITE_VERSION"))
        }
        dependencies.add("compileOnly", "$GROUP:alexandrite-plugin-sdk")
        dependencies.add("ksp", "$GROUP:alexandrite-ksp")
        dependencies.add("testImplementation", "$GROUP:alexandrite-testkit")
    }

    internal companion object {
        const val GROUP: String = "org.unitedsoftware.alexandrite"

        /** The Java release the host runs on and a plugin's classes target. */
        const val JAVA_RELEASE: Int = 21
    }
}
