package org.unitedsoftware.alexandrite.gradle

import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.process.CommandLineArgumentProvider

/** The `alexandrite.*` options of the KSP processor, read from an [AlexandriteExtension]. */
internal class KspOptions(
    @get:Input @get:Optional
    val pluginId: Provider<String>,
    @get:Input @get:Optional
    val pluginVersion: Provider<String>,
    @get:Input @get:Optional
    val indexPackage: Provider<String>,
    @get:Input @get:Optional
    val indexClass: Provider<String>,
) : CommandLineArgumentProvider {
    constructor(extension: AlexandriteExtension) :
        this(extension.pluginId, extension.pluginVersion, extension.indexPackage, extension.indexClass)

    override fun asArguments(): List<String> {
        val id = pluginId.orNull ?: throw GradleException(
            "The Alexandrite plugin has no id. Set it in the build file: alexandrite { pluginId = \"my-plugin\" }",
        )
        return listOfNotNull(
            "alexandrite.plugin=$id",
            "alexandrite.version=${pluginVersion.get()}",
            indexPackage.orNull?.let { "alexandrite.package=$it" },
            indexClass.orNull?.let { "alexandrite.indexClass=$it" },
        )
    }
}
