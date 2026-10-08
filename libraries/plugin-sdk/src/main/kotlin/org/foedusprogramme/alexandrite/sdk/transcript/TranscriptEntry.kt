package org.foedusprogramme.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import java.time.Instant

/** One entry of a conversation, which the store never changes once it holds it. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TranscriptEntry {
    /** Null until the entry is stored. */
    public val record: EntryRecord?
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
) : TranscriptEntry

/** A summary of the conversation that stands in for the entries it covers. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class SummaryEntry(
    override val record: EntryRecord?,
    public val text: String,
    /** The last entry the summary covers. */
    public val through: EntryId,
) : TranscriptEntry

/** A notice the agent sent the chat, which no model is sent. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class NoticeEntry(override val record: EntryRecord?, public val text: String, public val kind: NoticeKind) :
    TranscriptEntry

/** An entry of a type this version does not know, as stored. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class UnknownEntry(
    override val record: EntryRecord?,
    public val type: String,
    /** The stored object, its type included. */
    public val json: JsonObject,
) : TranscriptEntry

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
    }
}
