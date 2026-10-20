package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.TestToolContext
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** A workspace, a repository, a conversation directory and two places the floor protects (`secret`, `home/.ssh`) under [root]. */
class FsFixture(directory: Path) {
    val root: Path = directory.toRealPath()
    val workspace: Path = root.resolve("workspace")
    val repository: Path = root.resolve("repo")
    val secret: Path = root.resolve("secret")
    val home: Path = root.resolve("home")
    val conversation: Path = root.resolve("conversation")
    val floor = testHardFloor(listOf(secret), home)

    init {
        write("secret/key.txt", "SECRET")
        write("home/.ssh/id_rsa", "KEY")
        workspace.createDirectories()
        conversation.createDirectories()
    }

    fun write(relative: String, text: String): Path =
        root.resolve(relative).also { it.parent.createDirectories() }.also { it.writeText(text) }

    fun bytes(relative: String, bytes: ByteArray): Path =
        root.resolve(relative).also { it.parent.createDirectories() }.also { Files.write(it, bytes) }

    fun link(relative: String, target: Path): Path =
        root.resolve(relative).also { it.parent.createDirectories() }.also { Files.createSymbolicLink(it, target) }

    fun context(
        workspace: Path? = this.workspace,
        conversation: String = "test",
        conversationDirectory: Path = this.conversation,
        clearance: Clearance? = null,
    ): TestToolContext = testToolContext(
        testTurn(conversation = ConversationId(conversation)),
        workspace = workspace,
        conversationDirectory = conversationDirectory,
        clearance = clearance,
    )

    /** `refused: …` or `problem: …` from the review of the call, else its result, run with the review's snapshot. */
    fun reviewedRun(tool: ReviewedTool, arguments: JsonObject, conversation: String = "test"): String {
        val review = runBlocking { tool.review(arguments, context(conversation = conversation)) }
        review.refusal?.let { return "refused: $it" }
        review.problem?.let { return "problem: $it" }
        val cleared =
            context(conversation = conversation, clearance = Clearance(ClearanceKind.NONE, null, review.snapshot))
        val result = runBlocking { tool.execute(arguments, cleared) }
        return if (result.isError) "failed: ${result.text}" else result.text
    }
}

fun arguments(vararg values: Pair<String, Any>): JsonObject = JsonObject(
    values.associate { (name, value) ->
        name to when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Path -> JsonPrimitive(value.toString())
            is JsonElement -> value
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

fun hashOf(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }
}
