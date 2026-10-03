package org.foedusprogramme.alexandrite.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateBuiltInList : DefaultTask() {
    @get:Input
    abstract val kotlinSource: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val directory = root.resolve(BuiltInList.PACKAGE.replace('.', '/'))
        directory.mkdirs()
        directory.resolve(BuiltInList.FILE_NAME).writeText(kotlinSource.get())
    }
}
