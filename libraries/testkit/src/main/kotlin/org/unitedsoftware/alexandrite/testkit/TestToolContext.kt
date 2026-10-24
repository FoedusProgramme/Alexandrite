package org.unitedsoftware.alexandrite.testkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.unitedsoftware.alexandrite.internal.path.hasPosixPermissions
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.sandbox.Sandbox
import org.unitedsoftware.alexandrite.sdk.tool.BackgroundCall
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * By default, a message turn of a guest, in chat `test:main:chat` and conversation `test`, whose call is being
 * reviewed.
 */
public fun testToolContext(
    turn: TurnInfo = testTurn(),
    call: ToolCallId = ToolCallId("test-call"),
    workspace: Path? = null,
    conversationDirectory: Path = Path.of("/test/conversation"),
    clearance: Clearance? = null,
    background: BackgroundCall? = null,
    model: ModelRef? = null,
    sandbox: Sandbox? = null,
): TestToolContext =
    TestToolContext(turn, call, workspace, conversationDirectory, clearance, background, model, sandbox)

/** A tool context that records the paths and the untrusted content its call reports, valid until [end]. */
public class TestToolContext internal constructor(
    turn: TurnInfo,
    call: ToolCallId,
    workspace: Path?,
    conversationDirectory: Path,
    clearance: Clearance?,
    private val background: BackgroundCall?,
    model: ModelRef?,
    sandbox: Sandbox?,
) : ToolContext {
    private val reported = CopyOnWriteArrayList<Path>()
    private val reasons = CopyOnWriteArrayList<String>()

    @Volatile
    private var open = true

    override val turn: TurnInfo = turn
        get() = field.also { checkOpen() }

    override val call: ToolCallId = call
        get() = field.also { checkOpen() }

    override val workspace: Path? = workspace
        get() = field.also { checkOpen() }

    override val conversationDirectory: Path = conversationDirectory
        get() = field.also { checkOpen() }

    override val clearance: Clearance? = clearance
        get() = field.also { checkOpen() }

    override val model: ModelRef? = model
        get() = field.also { checkOpen() }

    override val sandbox: Sandbox? = sandbox
        get() = field.also { checkOpen() }

    /** The paths that the call reported as touched, in order. */
    public val touchedPaths: List<Path> get() = reported.toList()

    /** Why the call reported untrusted content, in order. */
    public val untrustedReasons: List<String> get() = reasons.toList()

    /** [workspace], created owner-only with the directories on the way when it does not exist. */
    override suspend fun openWorkspace(): Path {
        val path = checkNotNull(workspace) { "The tool context of call $call has no workspace." }
        return withContext(Dispatchers.IO) { createdOwnerOnly(path) }
    }

    /** [conversationDirectory], created owner-only with the directories on the way when it does not exist. */
    override suspend fun openConversationDirectory(): Path =
        withContext(Dispatchers.IO) { createdOwnerOnly(conversationDirectory) }

    override suspend fun conversationQuotaRefusal(path: Path, bytes: Long): String? = null

    override suspend fun background(): BackgroundCall? {
        checkOpen()
        return background
    }

    override fun touched(path: Path) {
        checkOpen()
        reported.add(path)
    }

    override fun untrusted(reason: String) {
        checkOpen()
        reasons.add(reason)
        tainted = true
    }

    /** Whether the turn's conversation took in untrusted content, as a test sets it or the call reports some. */
    @Volatile
    override var tainted: Boolean = false

    /** Ends the call, after which the context's members throw [IllegalStateException]. */
    public fun end() {
        open = false
    }

    private fun checkOpen() {
        check(open) { "A tool context is valid only while its call runs." }
    }
}

/** [path], created owner-only with the directories on the way when it does not exist. */
private fun createdOwnerOnly(path: Path): Path {
    if (Files.isDirectory(path)) return path
    if (hasPosixPermissions(path)) {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
    } else {
        Files.createDirectories(path)
    }
    return path
}

private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
