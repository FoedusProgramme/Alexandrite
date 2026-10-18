package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.unitedsoftware.alexandrite.internal.path.hardLinks
import org.unitedsoftware.alexandrite.internal.path.openRegularFile
import org.unitedsoftware.alexandrite.internal.path.writeAtomically
import org.unitedsoftware.alexandrite.internal.text.UnifiedDiff
import org.unitedsoftware.alexandrite.internal.text.Utf8Text
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Ends a write tool's call with [message], refused as a protected place is. */
internal class Blocked(message: String) : Refusal(message)

/** The file at [file], the canonical path of what a call names as [shown], as a write tool found it. */
internal class Target(
    val file: Path,
    val shown: Path,
    /** Null when nothing exists at [file]. */
    val current: Utf8Text?,
    /** The hash of the file's bytes, or [ABSENT]. */
    val snapshot: String,
    /** Whether [file] lies in the turn's own conversation directory, where a write is never gated outside `plan` mode. */
    val exempt: Boolean,
)

/** The snapshot of a file that does not exist. */
internal const val ABSENT = "absent"

/** The bytes of a file that the write tools change at most. */
internal const val MAX_WRITE_BYTES: Long = 16L * MIB

/** The review that [block] makes, a problem or a refusal for what it refuses. */
internal suspend fun reviewing(block: suspend () -> CallReview): CallReview = try {
    block()
} catch (e: Blocked) {
    CallReview(refusal = e.message)
} catch (e: Refusal) {
    CallReview(problem = e.message)
} catch (e: IOException) {
    currentCoroutineContext().ensureActive()
    CallReview(problem = readFailure(e))
}

/** What [block] returns, or the failed result that a [Refusal] or an I/O error makes. */
internal suspend fun writing(block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: Refusal) {
    ToolResult(e.message, isError = true)
} catch (e: IOException) {
    currentCoroutineContext().ensureActive()
    ToolResult(readFailure(e), isError = true)
}

/** The file that [argument] names for a write by the call of [context], once the floor allows it. */
internal suspend fun HardFloor.target(argument: String, context: ToolContext): Target {
    val shown = context.absolute(argument)
    val agent = context.turn.agent
    val conversation = context.turn.rootConversation
    return blocking {
        val file = when (val check = checkWrite(shown, agent, conversation)) {
            is FloorCheck.Allowed -> check.canonical
            is FloorCheck.Denied -> throw Blocked("$shown is protected (${check.reason}): no file tool may write it.")
        }
        val directory = context.conversationDirectory
        load(file, shown, file == directory || file.startsWith(directory))
    }
}

/** [file] (canonical), shown as [shown], as it is now, [exempt] when it lies in the conversation directory. */
private fun load(file: Path, shown: Path, exempt: Boolean): Target {
    val attributes = try {
        attributes(file)
    } catch (e: NoSuchFileException) {
        val directory = try {
            attributes(file.parent).isDirectory
        } catch (missing: NoSuchFileException) {
            false
        }
        if (!directory && !exempt) {
            throw Refusal("${shown.parent} is no existing directory: files are written only in existing directories.")
        }
        return Target(file, shown, null, ABSENT, exempt)
    }
    when {
        attributes.isDirectory -> throw Refusal("$shown is a directory.")

        !attributes.isRegularFile -> throw Refusal("$shown is no regular file, so it is not written.")

        hardLinks(file) > 1 -> throw Blocked(
            "$shown has more than one hard link, so it may be another name of a protected file: it is not written.",
        )
    }
    val bytes = openRegularFile(file).use { it.readNBytes(MAX_WRITE_BYTES.toInt() + 1) }
    if (bytes.size > MAX_WRITE_BYTES) {
        throw Refusal("$shown is over ${MAX_WRITE_BYTES / MIB} MiB, more than the write tools change.")
    }
    val text = Utf8Text.decode(bytes)
        ?: throw Refusal("$shown is no UTF-8 text (it looks binary): the write tools change text files only.")
    return Target(file, shown, text, sha256(bytes), exempt)
}

/** Refuses a write of the existing [target] unless [context]'s conversation read it as it is now. */
internal fun ReadLedger.checkRead(target: Target, context: ToolContext) {
    if (target.current == null) return
    when (hash(context.turn.conversation, target.file)) {
        null -> throw Refusal(
            "You have not read ${target.shown} in this conversation: read it with fs.read first, then write it.",
        )

        target.snapshot -> return

        else -> throw Refusal("${target.shown} changed since you read it: read it again with fs.read first.")
    }
}

/** What an approver sees of a call that makes [target] hold [text]. */
internal fun Target.review(text: String): CallReview =
    CallReview(preview = ReviewPreview.Diff(file, diff(text).text), snapshot = snapshot, exempt = exempt)

/** The diff from the file's text to [text], ignoring carriage returns unless only they change. */
internal fun Target.diff(text: String): UnifiedDiff {
    val old = current?.text.orEmpty()
    val oldLabel = if (current == null) "/dev/null" else "$file"
    val plain = UnifiedDiff.of(old.replace("\r\n", "\n"), text.replace("\r\n", "\n"), oldLabel, "$file")
    return if (plain.text.isEmpty()) UnifiedDiff.of(old, text, oldLabel, "$file") else plain
}

/** Refuses the call of [context] unless [target] is as the call's review found it. */
internal fun Target.checkReviewed(context: ToolContext) {
    val reviewed = context.clearance?.snapshot ?: throw Refusal("This call was not reviewed, so it writes nothing.")
    if (reviewed != snapshot) {
        throw Refusal("$shown changed since this call was reviewed: read it again with fs.read, then repeat the call.")
    }
}

/** Makes [target] hold [text], records the new file in [ledger] for the call of [context] and returns the diff. */
internal suspend fun Target.write(text: String, ledger: ReadLedger, context: ToolContext): UnifiedDiff {
    val bytes = current?.encode(text) ?: text.toByteArray(Charsets.UTF_8)
    if (exempt) {
        context.conversationQuotaRefusal(file, bytes.size.toLong())?.let { throw Refusal(it) }
    }
    val conversation = context.turn.conversation
    if (exempt) context.openConversationDirectory()
    blocking {
        try {
            if (exempt) Files.createDirectories(file.parent)
            writeAtomically(file) { it.write(bytes) }
        } catch (e: IOException) {
            val why = if (e is AccessDeniedException) "access denied" else e.message ?: e.javaClass.simpleName
            throw Refusal("$shown could not be written: $why.")
        }
    }
    ledger.record(conversation, file, sha256(bytes))
    context.touched(file)
    return diff(text)
}

/** `+added -removed lines` of [diff]. */
internal fun changed(diff: UnifiedDiff): String = "+${diff.added} -${diff.removed} lines"

private fun readFailure(e: IOException): String = when (e) {
    is NoSuchFileException -> "Nothing exists at ${e.file}."
    is AccessDeniedException -> "${e.file} cannot be read: access denied."
    else -> "A file could not be read: ${e.message ?: e.javaClass.simpleName}"
}
