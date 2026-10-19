package org.unitedsoftware.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import java.time.Instant

/** One entry of a conversation, which the store never changes once it holds it. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TranscriptEntry {
    /** Null until the entry is stored. */
    public val record: EntryRecord?

    /** This entry with [record] as its record. */
    public fun withRecord(record: EntryRecord): TranscriptEntry
}

/** The parts of this entry, empty for a kind that holds none. */
internal fun TranscriptEntry.parts(): List<Part> = when (this) {
    is UserEntry -> parts
    is AssistantEntry -> parts
    is ToolResultEntry -> content
    else -> emptyList()
}

/** Where and when the store keeps an entry. */
@Poko
public class EntryRecord(
    public val id: EntryId,
    public val conversation: ConversationId,
    public val turn: TurnId,
    public val createdAt: Instant,
)

/** Input that the model reads as the user's. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class UserEntry(
    override val record: EntryRecord?,
    public val parts: List<UserPart>,
    public val origin: UserOrigin,
) : TranscriptEntry {
    init {
        require(parts.isNotEmpty()) { "A user entry has at least one part." }
    }

    override fun withRecord(record: EntryRecord): UserEntry = UserEntry(record, parts, origin)
}

/** One response of a model. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class AssistantEntry(
    override val record: EntryRecord?,
    public val parts: List<AssistantPart>,
    public val producedBy: ModelRef,
    public val providerData: ProviderData = ProviderData.EMPTY,
) : TranscriptEntry {
    /** Whether the response holds neither visible text nor a tool call. */
    public val blank: Boolean get() = parts.none { (it is TextPart && it.text.isNotBlank()) || it is ToolCallPart }

    override fun withRecord(record: EntryRecord): AssistantEntry =
        AssistantEntry(record, parts, producedBy, providerData)
}

/** The result of one tool call. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class ToolResultEntry(
    override val record: EntryRecord?,
    public val callId: ToolCallId,
    /** The name the call gave. */
    public val toolName: String,
    public val content: List<ToolOutputPart>,
    public val outcome: ToolOutcome,
) : TranscriptEntry {
    override fun withRecord(record: EntryRecord): ToolResultEntry =
        ToolResultEntry(record, callId, toolName, content, outcome)
}

/** A summary of the conversation that stands in for the entries it covers. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class SummaryEntry(
    override val record: EntryRecord?,
    public val text: String,
    /** The last entry the summary covers. */
    public val through: EntryId,
    /** The entries before [through] that the summary does not cover, which requests read right after it. */
    public val kept: List<EntryId> = emptyList(),
) : TranscriptEntry {
    @Deprecated(message = "Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(record: EntryRecord?, text: String, through: EntryId) : this(record, text, through, emptyList())

    init {
        require(kept.all { it.value < through.value } && kept.toSet().size == kept.size) {
            "A summary keeps distinct entries before the last one it covers, was $kept through $through."
        }
    }

    /** Whether the summary stands in for the entry [id]. */
    public fun covers(id: EntryId): Boolean = id.value <= through.value && id !in kept

    override fun withRecord(record: EntryRecord): SummaryEntry = SummaryEntry(record, text, through, kept)
}

/** A notice the agent sent the chat, which no model is sent. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class NoticeEntry(override val record: EntryRecord?, public val text: String, public val kind: NoticeKind) :
    TranscriptEntry {
    override fun withRecord(record: EntryRecord): NoticeEntry = NoticeEntry(record, text, kind)
}

/** An entry of a type this version does not know, as stored. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class UnknownEntry(
    override val record: EntryRecord?,
    public val type: String,
    /** The stored object, its type included. */
    public val json: JsonObject,
) : TranscriptEntry {
    /** Keeps [json] as it was read. */
    override fun withRecord(record: EntryRecord): UnknownEntry = UnknownEntry(record, type, json)
}

/** Why the agent sent a notice. */
@JvmInline
@Serializable
public value class NoticeKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The model answered with neither text nor a tool call. */
        public val BLANK_REPLY: NoticeKind = NoticeKind("blank_reply")

        /** A hook stopped the turn. */
        public val HOOK_ABORTED: NoticeKind = NoticeKind("hook_aborted")

        /** The turn failed. */
        public val FAILED: NoticeKind = NoticeKind("failed")

        /** The model refused to answer. */
        public val REFUSED: NoticeKind = NoticeKind("refused")

        /** The runtime stopped while the turn ran. */
        public val INTERRUPTED: NoticeKind = NoticeKind("interrupted")

        /** The model's answer was cut off at its output limit. */
        public val OUTPUT_LIMIT: NoticeKind = NoticeKind("output_limit")

        /** The reply was too long for its chat. */
        public val TOO_LONG: NoticeKind = NoticeKind("too_long")

        /** The turn used up its tool rounds. */
        public val ROUND_LIMIT: NoticeKind = NoticeKind("round_limit")

        /** The turn used up its token budget. */
        public val BUDGET: NoticeKind = NoticeKind("budget")

        /** A folder's project instructions were not loaded, as the folder is not trusted. */
        public val UNTRUSTED_FOLDER: NoticeKind = NoticeKind("untrusted_folder")

        /** The turn's model failed, so the next of its agent's models answered. */
        public val MODEL_SWITCHED: NoticeKind = NoticeKind("model_switched")

        /** The final reply did not reach its chat. */
        public val UNDELIVERED: NoticeKind = NoticeKind("undelivered")

        /** The values this version knows. */
        public val entries: List<NoticeKind> = listOf(
            BLANK_REPLY,
            HOOK_ABORTED,
            FAILED,
            REFUSED,
            INTERRUPTED,
            OUTPUT_LIMIT,
            TOO_LONG,
            ROUND_LIMIT,
            BUDGET,
            UNTRUSTED_FOLDER,
            MODEL_SWITCHED,
            UNDELIVERED,
        )

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): NoticeKind = NoticeKind(id)
    }
}
