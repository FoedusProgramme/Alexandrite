package org.unitedsoftware.alexandrite.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

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
    }
}
