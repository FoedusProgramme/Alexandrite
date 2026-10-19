package org.unitedsoftware.alexandrite.sdk.tool

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import java.nio.file.Path

/** A tool that reviews each call before the permission layer decides it. */
public interface ReviewedTool : Tool {
    /** Checks the call without acting, within the tool's timeout. */
    public suspend fun review(arguments: JsonObject, context: ToolContext): CallReview
}

/** What a [ReviewedTool] found out about a call. */
@Poko
public class CallReview(
    /** The risk of this call, null for the declared one. */
    public val risk: ToolRisk? = null,
    /** Why the call is refused without asking anyone, null when it is not. */
    public val refusal: String? = null,
    /** What an approver is shown. */
    public val preview: ReviewPreview? = null,
    /** What the tool compares again before it acts. */
    public val snapshot: String? = null,
    /** What allowing such calls for good would cover, null when it may not be offered. */
    public val rule: RuleSubject? = null,
    /** Why the call cannot run as asked, null when it can. */
    public val problem: String? = null,
    /** Whether the call needs no approval outside `plan` mode, as a write in the turn's own conversation directory does. */
    public val exempt: Boolean = false,
    /** Whether the call runs as a background job from its start, which [ToolContext.background] then hands it. */
    public val background: Boolean = false,
    /** Whether the call's output joins the conversation as untrusted content, which [ToolContext.untrusted] reports. */
    public val untrusted: Boolean = false,
    /** Why only a person may allow the call whatever the mode, null when a mode, a rule or the judge may. */
    public val person: String? = null,
) {
    @Deprecated(message = "Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        risk: ToolRisk? = null,
        refusal: String? = null,
        preview: ReviewPreview? = null,
        snapshot: String? = null,
        rule: RuleSubject? = null,
        problem: String? = null,
        exempt: Boolean = false,
        background: Boolean = false,
        untrusted: Boolean = false,
    ) : this(risk, refusal, preview, snapshot, rule, problem, exempt, background, untrusted, null)

    @Deprecated(message = "Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        risk: ToolRisk? = null,
        refusal: String? = null,
        preview: ReviewPreview? = null,
        snapshot: String? = null,
        rule: RuleSubject? = null,
        problem: String? = null,
        exempt: Boolean = false,
        background: Boolean = false,
    ) : this(risk, refusal, preview, snapshot, rule, problem, exempt, background, false, null)

    @Deprecated(message = "Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        risk: ToolRisk? = null,
        refusal: String? = null,
        preview: ReviewPreview? = null,
        snapshot: String? = null,
        rule: RuleSubject? = null,
        problem: String? = null,
        exempt: Boolean = false,
    ) : this(risk, refusal, preview, snapshot, rule, problem, exempt, false, false, null)

    @Deprecated(message = "Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        risk: ToolRisk? = null,
        refusal: String? = null,
        preview: ReviewPreview? = null,
        snapshot: String? = null,
        rule: RuleSubject? = null,
        problem: String? = null,
    ) : this(risk, refusal, preview, snapshot, rule, problem, false, false, false, null)
}

/** What an approver is shown of a call. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ReviewPreview {
    /** A change of the file [path]. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Diff(public val path: Path, public val unified: String) : ReviewPreview

    /** A program run. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Command(
        public val argv: List<String>,
        public val cwd: Path,
        /** The real path of the program, null when it cannot be resolved. */
        public val executable: Path?,
        /** Whether the program runs code that the floor cannot see, such as a shell's. */
        public val opaque: Boolean,
        /** The steps that the call runs in turn in place of [argv], empty when it runs [argv] itself. */
        public val segments: List<CommandSegment> = emptyList(),
    ) : ReviewPreview {
        @Deprecated(message = "Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
        public constructor(argv: List<String>, cwd: Path, executable: Path?, opaque: Boolean) :
            this(argv, cwd, executable, opaque, emptyList())

        init {
            require(argv.isNotEmpty()) { "A command names its program." }
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Text(public val text: String) : ReviewPreview

    /** The file [path] sent to [destination]. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class OutgoingFile(
        public val path: Path,
        /** In bytes. */
        public val size: Long,
        /** The SHA-256 of the file's bytes in lowercase hex. */
        public val sha256: String,
        public val destination: ChatAddress,
        /** The text sent with the file, null for none. */
        public val caption: String? = null,
    ) : ReviewPreview {
        init {
            require(size >= 0) { "A file holds at least 0 bytes, was $size." }
        }
    }
}

/** One step of a [ReviewPreview.Command] that runs several: a program, or a change of directory. */
@Poko
public class CommandSegment(
    public val argv: List<String>,
    /** Where the program runs, or where a change of directory leads. */
    public val cwd: Path,
    /** The real path of the program, null for a change of directory or a program that cannot be resolved. */
    public val executable: Path?,
    /** How the step follows the one before it, null for the first. */
    public val joint: SegmentJoint?,
    /** Whether the program runs code that the floor cannot see. */
    public val opaque: Boolean = false,
    /** Whether the step only changes the directory of the steps after it. */
    public val changesDirectory: Boolean = false,
    /** Whether the program only reads, so that it needs no approval. */
    public val readOnly: Boolean = false,
    /** Whether the program's output joins the conversation as untrusted content. */
    public val untrusted: Boolean = false,
    /** The variables that the program gets beside the usual ones. */
    public val environment: Map<String, String> = emptyMap(),
    /** What allowing such programs for good would cover, null when it may not be offered. */
    public val rule: RuleSubject? = null,
) {
    init {
        require(argv.isNotEmpty()) { "A command segment names its program." }
    }
}

/** How a [CommandSegment] follows the one before it. */
public enum class SegmentJoint(
    /** As a shell writes it. */
    public val operator: String,
) {
    /** It runs only when the one before succeeded. */
    AND("&&"),

    /** It runs only when the one before failed. */
    OR("||"),

    /** It runs whatever the one before did. */
    THEN(";"),
}

/** The calls that an allow rule covers: those of [tool] whose subject starts with [tokens]. */
@Poko
public class RuleSubject(
    public val tool: String,
    /** Such as a program and its first word, empty for every call of [tool]. */
    public val tokens: List<String>,
    /** How people are shown the rule. */
    public val display: String,
) {
    init {
        require(ToolNames.PATTERN.matches(tool)) { "Malformed tool name '$tool'." }
        require(display.isNotBlank()) { "A rule subject of $tool shows some text." }
    }
}
