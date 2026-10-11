package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** By default, a message turn of a member who is no admin, in chat `test:main:chat` and conversation `test`. */
public fun testToolContext(
    turn: TurnInfo = testTurn(),
    call: ToolCallId = ToolCallId("test-call"),
    workspace: Path? = null,
): TestToolContext = TestToolContext(turn, call, workspace)

/** A tool context that records the paths its call reports. */
public class TestToolContext internal constructor(
    override val turn: TurnInfo,
    override val call: ToolCallId,
    override val workspace: Path?,
) : ToolContext {
    private val reported = CopyOnWriteArrayList<Path>()

    /** The paths that the call reported as touched, in order. */
    public val touchedPaths: List<Path> get() = reported.toList()

    /** [workspace], created with the directories on the way when it does not exist. */
    override suspend fun openWorkspace(): Path {
        val path = checkNotNull(workspace) { "The tool context of call $call has no workspace." }
        return withContext(Dispatchers.IO) { Files.createDirectories(path) }
    }

    override fun touched(path: Path) {
        reported.add(path)
    }
}
