package org.foedusprogramme.alexandrite.tools.fs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.testkit.TestToolContext
import org.foedusprogramme.alexandrite.testkit.testHardFloor
import org.foedusprogramme.alexandrite.testkit.testToolContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** A workspace, a repository and two places the floor protects (`secret`, `home/.ssh`) under [root]. */
class FsFixture(directory: Path) {
    val root: Path = directory.toRealPath()
    val workspace: Path = root.resolve("workspace")
    val repository: Path = root.resolve("repo")
    val secret: Path = root.resolve("secret")
    val home: Path = root.resolve("home")
    val floor = testHardFloor(listOf(secret), home)

    init {
        write("secret/key.txt", "SECRET")
        write("home/.ssh/id_rsa", "KEY")
        workspace.createDirectories()
    }

    fun write(relative: String, text: String): Path =
        root.resolve(relative).also { it.parent.createDirectories() }.also { it.writeText(text) }

    fun bytes(relative: String, bytes: ByteArray): Path =
        root.resolve(relative).also { it.parent.createDirectories() }.also { Files.write(it, bytes) }

    fun link(relative: String, target: Path): Path =
        root.resolve(relative).also { it.parent.createDirectories() }.also { Files.createSymbolicLink(it, target) }

    fun context(workspace: Path? = this.workspace): TestToolContext = testToolContext(workspace = workspace)
}

fun arguments(vararg values: Pair<String, Any>): JsonObject = JsonObject(
    values.associate { (name, value) ->
        name to when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Path -> JsonPrimitive(value.toString())
            else -> error("No JSON for $value")
        }
    },
)

fun Tool.call(context: TestToolContext, vararg values: Pair<String, Any>): ToolResult =
    runBlocking { execute(arguments(*values), context) }

val ToolResult.text: String get() = content.joinToString("") { (it as TextPart).text }

fun assertRefused(expected: String, result: ToolResult) {
    assertEquals(true to expected, result.isError to result.text)
}
