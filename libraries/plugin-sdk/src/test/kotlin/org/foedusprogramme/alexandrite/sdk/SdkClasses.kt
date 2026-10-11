package org.foedusprogramme.alexandrite.sdk

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo

internal val sdkClasses: List<Class<*>> by lazy {
    val root = Path.of(AlexandriteSdk::class.java.protectionDomain.codeSource.location.toURI())
    Files.walk(root).use { files ->
        files.filter { it.extension == "class" }
            .map { it.relativeTo(root).invariantSeparatorsPathString.removeSuffix(".class").replace('/', '.') }
            .map { Class.forName(it, false, AlexandriteSdk::class.java.classLoader) }
            .toList()
    }
}
