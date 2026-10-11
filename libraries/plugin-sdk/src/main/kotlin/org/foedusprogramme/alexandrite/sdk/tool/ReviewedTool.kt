package org.foedusprogramme.alexandrite.sdk.tool

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
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
)

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
    ) : ReviewPreview {
        init {
            require(argv.isNotEmpty()) { "A command names its program." }
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Text(public val text: String) : ReviewPreview
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
