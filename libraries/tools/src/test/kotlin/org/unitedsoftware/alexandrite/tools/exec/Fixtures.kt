package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.TestToolContext
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** A workspace, a home with SSH keys and a protected place `secret` under [directory], and the floor that knows them. */
internal class ExecFixture(directory: Path) {
    val root: Path = directory.toRealPath()
    val workspace: Path = root.resolve("workspace").createDirectories()
    val home: Path = root.resolve("home")
    val secret: Path = root.resolve("secret")
    val floor: HardFloor = testHardFloor(listOf(secret), home)

    init {
        write("home/.ssh/id_rsa", "PRIVATE KEY")
        write("secret/key.txt", "SECRET")
    }

    fun write(relative: String, text: String): Path =
        root.resolve(relative).also { it.parent.createDirectories() }.also { it.writeText(text) }

    /** An executable shell script at [relative] that runs [body]. */
    fun script(relative: String, body: String): Path = write(relative, "#!/bin/sh\n$body\n").also {
        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
    }

    fun tool(config: ExecConfig = ExecConfig(), floor: HardFloor = this.floor): ExecTool = ExecTool(floor, config)

    fun context(
        turn: TurnInfo = testTurn(),
        clearance: Clearance? = null,
        workspace: Path? = this.workspace,
    ): TestToolContext = testToolContext(turn = turn, workspace = workspace, clearance = clearance)

    /** A context whose call an approver allowed, with the review's [snapshot] when given. */
    fun allowed(snapshot: String? = null): TestToolContext =
        context(clearance = Clearance(ClearanceKind.HUMAN, testUser("admin", isAdmin = true), snapshot))
}

/** A message turn of an administrator. */
fun operatorTurn(kind: TurnKind = TurnKind.MESSAGE): TurnInfo =
    testTurn(kind) { actor(testUser("admin", isAdmin = true)) }

fun arguments(vararg values: Pair<String, Any>): JsonObject = JsonObject(
    values.associate { (name, value) ->
        name to when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Path -> JsonPrimitive(value.toString())
            is List<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
            else -> error("No JSON for $value")
        }
    },
)

internal fun ExecTool.run(context: TestToolContext, vararg values: Pair<String, Any>): ToolResult =
    runBlocking { execute(arguments(*values), context) }

internal fun ExecTool.check(context: TestToolContext, vararg values: Pair<String, Any>): CallReview =
    runBlocking { review(arguments(*values), context) }

val ToolResult.text: String get() = content.joinToString("") { (it as TextPart).text }
