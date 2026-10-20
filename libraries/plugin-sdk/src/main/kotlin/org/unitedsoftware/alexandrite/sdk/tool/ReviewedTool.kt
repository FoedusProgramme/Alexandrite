package org.unitedsoftware.alexandrite.sdk.tool

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.sandbox.Contained
import java.nio.file.Path

/** A tool that reviews each call before the permission layer decides it. */
public interface ReviewedTool : Tool {
    /** Checks the call without acting, within the tool's timeout. */
    public suspend fun review(arguments: JsonObject, context: ToolContext): CallReview
}

/** What a [ReviewedTool] found out about a call. */
@Poko
public class CallReview private constructor(
    /** The risk of this call, null for the declared one. */
    public val risk: ToolRisk?,
    /** Why the call is refused without asking anyone, null when it is not. */
    public val refusal: String?,
    /** What an approver is shown. */
    public val preview: ReviewPreview?,
    /** What the tool compares again before it acts. */
    public val snapshot: String?,
    /** What allowing such calls for good would cover, null when it may not be offered. */
    public val rule: RuleSubject?,
    /** Why the call cannot run as asked, null when it can. */
    public val problem: String?,
    /** Whether the call needs no approval outside `plan` mode, as a write in the turn's own conversation directory does. */
    public val exempt: Boolean,
    /** Whether the call runs as a background job from its start, which [ToolContext.background] then hands it. */
    public val background: Boolean,
    /** Whether the call's output joins the conversation as untrusted content, which [ToolContext.untrusted] reports. */
    public val untrusted: Boolean,
    /** Why only a person may allow the call whatever the mode, null when a mode, a rule or the judge may. */
    public val person: String?,
    /** The sandbox the call runs in, null when it runs on the host. */
    public val contained: Contained?,
) {
    public fun toBuilder(): Builder = Builder()
        .risk(risk)
        .refusal(refusal)
        .preview(preview)
        .snapshot(snapshot)
        .rule(rule)
        .problem(problem)
        .exempt(exempt)
        .background(background)
        .untrusted(untrusted)
        .person(person)
        .contained(contained)

    public class Builder internal constructor() {
        private var risk: ToolRisk? = null
        private var refusal: String? = null
        private var preview: ReviewPreview? = null
        private var snapshot: String? = null
        private var rule: RuleSubject? = null
        private var problem: String? = null
        private var exempt: Boolean = false
        private var background: Boolean = false
        private var untrusted: Boolean = false
        private var person: String? = null
        private var contained: Contained? = null

        public fun risk(risk: ToolRisk?): Builder = apply { this.risk = risk }

        public fun refusal(refusal: String?): Builder = apply { this.refusal = refusal }

        public fun preview(preview: ReviewPreview?): Builder = apply { this.preview = preview }

        public fun snapshot(snapshot: String?): Builder = apply { this.snapshot = snapshot }

        public fun rule(rule: RuleSubject?): Builder = apply { this.rule = rule }

        public fun problem(problem: String?): Builder = apply { this.problem = problem }

        public fun exempt(exempt: Boolean): Builder = apply { this.exempt = exempt }

        public fun background(background: Boolean): Builder = apply { this.background = background }

        public fun untrusted(untrusted: Boolean): Builder = apply { this.untrusted = untrusted }

        public fun person(person: String?): Builder = apply { this.person = person }

        public fun contained(contained: Contained?): Builder = apply { this.contained = contained }

        public fun build(): CallReview = CallReview(
            risk,
            refusal,
            preview,
            snapshot,
            rule,
            problem,
            exempt,
            background,
            untrusted,
            person,
            contained,
        )
    }

    public companion object {
        public fun builder(): Builder = Builder()
    }
}

public inline fun CallReview.rebuild(block: CallReview.Builder.() -> Unit): CallReview =
    toBuilder().apply(block).build()

/** What an approver is shown of a call. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ReviewPreview {
    /** A change of the file [path]. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Diff(public val path: Path, public val unified: String) : ReviewPreview

    /** A program run: the command line as the call writes it, and the steps it is parsed into when it has any. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Command private constructor(
        /** The command line as the call writes it, which runs itself only when [segments] is empty. */
        public val argv: List<String>,
        /** The working directory of the call. */
        public val cwd: Path,
        /** The real path of [argv]'s program, null when it cannot be resolved. */
        public val executable: Path?,
        /** Whether [argv]'s program runs code that the floor cannot see, such as a shell's. */
        public val opaque: Boolean,
        /** The steps that the call runs in turn in place of [argv], empty when it runs [argv] itself. */
        public val segments: List<CommandSegment>,
    ) : ReviewPreview {
        init {
            require(argv.isNotEmpty()) { "A command names its program." }
        }

        public companion object {
            public operator fun invoke(
                argv: List<String>,
                cwd: Path,
                executable: Path?,
                opaque: Boolean,
                segments: List<CommandSegment> = emptyList(),
            ): Command = Command(argv.toList(), cwd, executable, opaque, segments.toList())
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
public class CommandSegment private constructor(
    public val argv: List<String>,
    /** Where the program runs, or where a change of directory leads. */
    public val cwd: Path,
    /** The real path of the program, null for a change of directory or a program that cannot be resolved. */
    public val executable: Path?,
    /** How the step follows the one before it, null for the first. */
    public val joint: SegmentJoint?,
    /** Whether the program runs code that the floor cannot see. */
    public val opaque: Boolean,
    /** Whether the step only changes the directory of the steps after it. */
    public val changesDirectory: Boolean,
    /** Whether the program only reads, so that it needs no approval. */
    public val readOnly: Boolean,
    /** Whether the program's output joins the conversation as untrusted content. */
    public val untrusted: Boolean,
    /** The variables that the program gets beside the usual ones. */
    public val environment: Map<String, String>,
    /** What allowing such programs for good would cover, null when it may not be offered. */
    public val rule: RuleSubject?,
) {
    init {
        require(argv.isNotEmpty()) { "A command segment names its program." }
    }

    public companion object {
        public operator fun invoke(
            argv: List<String>,
            cwd: Path,
            executable: Path?,
            joint: SegmentJoint?,
            opaque: Boolean = false,
            changesDirectory: Boolean = false,
            readOnly: Boolean = false,
            untrusted: Boolean = false,
            environment: Map<String, String> = emptyMap(),
            rule: RuleSubject? = null,
        ): CommandSegment = CommandSegment(
            argv.toList(),
            cwd,
            executable,
            joint,
            opaque,
            changesDirectory,
            readOnly,
            untrusted,
            environment.toMap(),
            rule,
        )
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
public class RuleSubject private constructor(
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

    public companion object {
        public operator fun invoke(tool: String, tokens: List<String>, display: String): RuleSubject =
            RuleSubject(tool, tokens.toList(), display)
    }
}
