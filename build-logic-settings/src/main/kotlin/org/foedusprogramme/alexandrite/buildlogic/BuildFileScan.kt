package org.foedusprogramme.alexandrite.buildlogic

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

/**
 * [AlexandriteLayout.scan] over the real file system under [Parameters.rootDirectory]. A value source, so the
 * configuration cache re-runs this cheap scan on every build and is invalidated exactly when its result changes.
 */
abstract class BuildFileScan : ValueSource<List<String>, BuildFileScan.Parameters> {
    interface Parameters : ValueSourceParameters {
        val rootDirectory: DirectoryProperty
    }

    override fun obtain(): List<String> {
        val root = parameters.rootDirectory.get().asFile
        return AlexandriteLayout.scan(object : ModuleTree {
            override fun hasBuildFile(directory: String) = File(root, "$directory/${AlexandriteLayout.BUILD_FILE}").isFile

            override fun subdirectories(directory: String) =
                File(root, directory).listFiles(File::isDirectory)?.map { it.name }.orEmpty()
        })
    }
}
