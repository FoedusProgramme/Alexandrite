package org.foedusprogramme.alexandrite.sdk.transcript

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ForwardKind
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.QuoteTrust
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.reflect.KClass

private const val TYPE = "type"
private const val RECORD = "record"

private val TEXT_PART = form(TextPartForm.serializer(), ::TextPartForm, TextPartForm::toPart)

private val CONTEXT_PART = form(ContextPartForm.serializer(), ::ContextPartForm, ContextPartForm::toPart)

private val MEDIA_PART = form(MediaPartForm.serializer(), ::MediaPartForm, MediaPartForm::toPart)

internal val transcriptJson: Json = Json {
    classDiscriminator = TYPE
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    serializersModule = SerializersModule {
        hierarchy(TranscriptEntry::class, ::unknownEntry, UnknownEntry::type, ::rawEntry) {
            subclass(UserEntry::class, form(UserEntryForm.serializer(), ::UserEntryForm, UserEntryForm::toEntry))
            subclass(
                AssistantEntry::class,
                form(AssistantEntryForm.serializer(), ::AssistantEntryForm, AssistantEntryForm::toEntry),
            )
            subclass(
                ToolResultEntry::class,
                form(ToolResultEntryForm.serializer(), ::ToolResultEntryForm, ToolResultEntryForm::toEntry),
            )
            subclass(
                SummaryEntry::class,
                form(SummaryEntryForm.serializer(), ::SummaryEntryForm, SummaryEntryForm::toEntry),
            )
            subclass(
                NoticeEntry::class,
                form(NoticeEntryForm.serializer(), ::NoticeEntryForm, NoticeEntryForm::toEntry),
            )
        }
        hierarchy(UserPart::class, ::UnknownPart, UnknownPart::type, UnknownPart::json) {
            subclass(TextPart::class, TEXT_PART)
            subclass(ContextPart::class, CONTEXT_PART)
            subclass(MediaPart::class, MEDIA_PART)
        }
        hierarchy(AssistantPart::class, ::UnknownPart, UnknownPart::type, UnknownPart::json) {
            subclass(TextPart::class, TEXT_PART)
            subclass(
                ReasoningPart::class,
                form(ReasoningPartForm.serializer(), ::ReasoningPartForm, ReasoningPartForm::toPart),
            )
            subclass(
                ToolCallPart::class,
                form(ToolCallPartForm.serializer(), ::ToolCallPartForm, ToolCallPartForm::toPart),
            )
            subclass(OpaquePart::class, form(OpaquePartForm.serializer(), ::OpaquePartForm, OpaquePartForm::toPart))
        }
        hierarchy(ToolOutputPart::class, ::UnknownPart, UnknownPart::type, UnknownPart::json) {
            subclass(TextPart::class, TEXT_PART)
            subclass(MediaPart::class, MEDIA_PART)
        }
        hierarchy(MediaSource::class, ::UnknownMedia, UnknownMedia::type, UnknownMedia::json) {
            subclass(
                StoredMedia::class,
                form(StoredMediaForm.serializer(), ::StoredMediaForm, StoredMediaForm::toSource),
            )
        }
        hierarchy(UserOrigin::class, UserOrigin::Unknown, UserOrigin.Unknown::type, UserOrigin.Unknown::json) {
            subclass(
                UserOrigin.FromChat::class,
                form(FromChatForm.serializer(), ::FromChatForm, FromChatForm::toOrigin),
            )
            subclass(
                UserOrigin.Initiated::class,
                form(InitiatedForm.serializer(), ::InitiatedForm, InitiatedForm::toOrigin),
            )
        }
        hierarchy(ToolOutcome::class, ToolOutcome::Unknown, ToolOutcome.Unknown::type, ToolOutcome.Unknown::json) {
            subclass(
                ToolOutcome.Succeeded::class,
                singleton(SucceededForm.serializer(), SucceededForm, ToolOutcome.Succeeded),
            )
            subclass(ToolOutcome.Failed::class, singleton(FailedForm.serializer(), FailedForm, ToolOutcome.Failed))
            subclass(
                ToolOutcome.Cancelled::class,
                singleton(CancelledForm.serializer(), CancelledForm, ToolOutcome.Cancelled),
            )
            subclass(ToolOutcome.NotRun::class, form(NotRunForm.serializer(), ::NotRunForm, NotRunForm::toOutcome))
        }
    }
}

/**
 * Registers the [known] subtypes of [base], and [unknown] for the rest: decoded from the object that was read,
 * encoded back to it.
 */
private inline fun <B : Any, reified U : B> SerializersModuleBuilder.hierarchy(
    base: KClass<B>,
    noinline unknown: (String, JsonObject) -> U,
    noinline typeOf: (U) -> String,
    noinline jsonOf: (U) -> JsonObject,
    known: PolymorphicModuleBuilder<B>.() -> Unit,
) {
    polymorphic(base) {
        known()
        defaultDeserializer { type -> type?.takeIf(String::isNotBlank)?.let { Fallback(it, unknown, jsonOf) } }
    }
    polymorphicDefaultSerializer(base) { value -> if (value is U) Fallback(typeOf(value), unknown, jsonOf) else null }
}

private class Fallback<B : Any, U : B>(
    type: String,
    private val unknown: (String, JsonObject) -> U,
    private val jsonOf: (U) -> JsonObject,
) : KSerializer<B> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(type)

    override fun serialize(encoder: Encoder, value: B) {
        @Suppress("UNCHECKED_CAST")
        (encoder as JsonEncoder).encodeJsonElement(JsonObject(jsonOf(value as U) - TYPE))
    }

    override fun deserialize(decoder: Decoder): B =
        unknown(descriptor.serialName, (decoder as JsonDecoder).decodeJsonElement().jsonObject)
}

private class FormSerializer<T : Any, F : Any>(
    private val form: KSerializer<F>,
    private val toForm: (T) -> F,
    private val fromForm: (F) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor get() = form.descriptor

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeSerializableValue(form, toForm(value))
    }

    override fun deserialize(decoder: Decoder): T = fromForm(decoder.decodeSerializableValue(form))
}

private fun <T : Any, F : Any> form(form: KSerializer<F>, toForm: (T) -> F, fromForm: (F) -> T): KSerializer<T> =
    FormSerializer(form, toForm, fromForm)

private fun <T : Any, F : Any> singleton(form: KSerializer<F>, formValue: F, value: T): KSerializer<T> =
    FormSerializer(form, { formValue }, { value })

private fun unknownEntry(type: String, json: JsonObject): UnknownEntry {
    val record = json[RECORD]?.let {
        try {
            transcriptJson.decodeFromJsonElement(RecordForm.serializer(), it).toRecord()
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    return UnknownEntry(record, type, json)
}

/** The stored object of [entry], with its record as the entry has it. */
private fun rawEntry(entry: UnknownEntry): JsonObject {
    val record = entry.record ?: return JsonObject(entry.json - RECORD)
    return JsonObject(
        entry.json + (RECORD to transcriptJson.encodeToJsonElement(RecordForm.serializer(), RecordForm(record))),
    )
}

private object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.foedusprogramme.alexandrite.sdk.transcript.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw SerializationException("Malformed instant '$text'.", e)
        }
    }
}

private fun Map<Dialect, JsonObject>?.toProviderData(): ProviderData = ProviderData(orEmpty())

private fun ProviderData.toForm(): Map<Dialect, JsonObject>? = entries.takeIf { it.isNotEmpty() }

@Serializable
private class RecordForm(
    val id: EntryId,
    val conversation: ConversationId,
    val turn: TurnId,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
) {
    constructor(record: EntryRecord) : this(record.id, record.conversation, record.turn, record.createdAt)

    fun toRecord(): EntryRecord = EntryRecord(id, conversation, turn, createdAt)
}

@Serializable
@SerialName("user")
private class UserEntryForm(val record: RecordForm? = null, val parts: List<UserPart>, val origin: UserOrigin) {
    constructor(entry: UserEntry) : this(entry.record?.let(::RecordForm), entry.parts, entry.origin)

    fun toEntry(): UserEntry = UserEntry(record?.toRecord(), parts, origin)
}

@Serializable
@SerialName("assistant")
private class AssistantEntryForm(
    val record: RecordForm? = null,
    val parts: List<AssistantPart>,
    val producedBy: ModelRef,
    val providerData: Map<Dialect, JsonObject>? = null,
) {
    constructor(entry: AssistantEntry) :
        this(entry.record?.let(::RecordForm), entry.parts, entry.producedBy, entry.providerData.toForm())

    fun toEntry(): AssistantEntry = AssistantEntry(record?.toRecord(), parts, producedBy, providerData.toProviderData())
}

@Serializable
@SerialName("tool_result")
private class ToolResultEntryForm(
    val record: RecordForm? = null,
    val callId: ToolCallId,
    val toolName: String,
    val content: List<ToolOutputPart>,
    val outcome: ToolOutcome,
) {
    constructor(entry: ToolResultEntry) :
        this(entry.record?.let(::RecordForm), entry.callId, entry.toolName, entry.content, entry.outcome)

    fun toEntry(): ToolResultEntry = ToolResultEntry(record?.toRecord(), callId, toolName, content, outcome)
}

@Serializable
@SerialName("summary")
private class SummaryEntryForm(val record: RecordForm? = null, val text: String, val through: EntryId) {
    constructor(entry: SummaryEntry) : this(entry.record?.let(::RecordForm), entry.text, entry.through)

    fun toEntry(): SummaryEntry = SummaryEntry(record?.toRecord(), text, through)
}

@Serializable
@SerialName("notice")
private class NoticeEntryForm(val record: RecordForm? = null, val text: String, val kind: NoticeKind) {
    constructor(entry: NoticeEntry) : this(entry.record?.let(::RecordForm), entry.text, entry.kind)

    fun toEntry(): NoticeEntry = NoticeEntry(record?.toRecord(), text, kind)
}

@Serializable
@SerialName("from_chat")
private class FromChatForm(
    val sender: ChatUserForm,
    val message: ChannelMessageRef,
    @Serializable(with = InstantSerializer::class) val receivedAt: Instant,
    val forwarded: ForwardOriginForm? = null,
    val quote: QuoteForm? = null,
) {
    constructor(origin: UserOrigin.FromChat) : this(
        ChatUserForm(origin.sender),
        origin.message,
        origin.receivedAt,
        origin.forwarded?.let(::ForwardOriginForm),
        origin.quote?.let(::QuoteForm),
    )

    fun toOrigin(): UserOrigin.FromChat =
        UserOrigin.FromChat(sender.toUser(), message, receivedAt, forwarded?.toOrigin(), quote?.toQuote())
}

@Serializable
@SerialName("initiated")
private class InitiatedForm(val plugin: String, val kind: TurnKind) {
    constructor(origin: UserOrigin.Initiated) : this(origin.plugin, origin.kind)

    fun toOrigin(): UserOrigin.Initiated = UserOrigin.Initiated(plugin, kind)
}

@Serializable
private class ChatUserForm(
    val address: UserAddress,
    val displayName: String,
    val username: String? = null,
    val isBot: Boolean,
    val isAdmin: Boolean,
) {
    constructor(user: ChatUser) : this(user.address, user.displayName, user.username, user.isBot, user.isAdmin)

    fun toUser(): ChatUser = ChatUser(address, displayName, username, isBot, isAdmin)
}

@Serializable
private class ForwardOriginForm(
    val kind: ForwardKind,
    val name: String? = null,
    val user: UserAddress? = null,
    val chat: ChatAddress? = null,
) {
    constructor(origin: ForwardOrigin) : this(origin.kind, origin.name, origin.user, origin.chat)

    fun toOrigin(): ForwardOrigin = ForwardOrigin(kind, name, user, chat)
}

@Serializable
private class QuoteForm(
    val text: String,
    val senderName: String? = null,
    val sender: UserAddress? = null,
    val senderIsBot: Boolean? = null,
    val target: ChannelMessageRef? = null,
    val external: Boolean,
    val trust: QuoteTrustForm,
) {
    constructor(quote: Quote) : this(
        quote.text,
        quote.senderName,
        quote.sender,
        quote.senderIsBot,
        quote.target,
        quote.external,
        QuoteTrustForm(quote.trust.live, quote.trust.content),
    )

    fun toQuote(): Quote = Quote.builder(text)
        .senderName(senderName)
        .sender(sender)
        .senderIsBot(senderIsBot)
        .target(target)
        .external(external)
        .trust(QuoteTrust(trust.live, trust.content))
        .build()
}

@Serializable
private class QuoteTrustForm(val live: Boolean, val content: Boolean)

@Serializable
@SerialName("text")
private class TextPartForm(val text: String) {
    constructor(part: TextPart) : this(part.text)

    fun toPart(): TextPart = TextPart(text)
}

@Serializable
@SerialName("context")
private class ContextPartForm(val source: String, val text: String) {
    constructor(part: ContextPart) : this(part.source, part.text)

    fun toPart(): ContextPart = ContextPart(source, text)
}

@Serializable
@SerialName("media")
private class MediaPartForm(
    val kind: MediaKind,
    val mediaType: String,
    val source: MediaSource,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    constructor(part: MediaPart) : this(part.kind, part.mediaType, part.source, part.name, part.width, part.height)

    fun toPart(): MediaPart = MediaPart(kind, mediaType, source, name, width, height)
}

@Serializable
@SerialName("reasoning")
private class ReasoningPartForm(val text: String? = null, val summary: String? = null, val seal: SealForm? = null) {
    constructor(part: ReasoningPart) : this(part.text, part.summary, part.seal?.let(::SealForm))

    fun toPart(): ReasoningPart = ReasoningPart(text, summary, seal?.toSeal())
}

@Serializable
private class SealForm(
    val origin: ModelRef,
    val dialect: Dialect,
    val kind: SealKind,
    val data: String,
    val providerData: Map<Dialect, JsonObject>? = null,
) {
    constructor(seal: ReasoningSeal) : this(seal.origin, seal.dialect, seal.kind, seal.data, seal.providerData.toForm())

    fun toSeal(): ReasoningSeal = ReasoningSeal(origin, dialect, kind, data, providerData.toProviderData())
}

@Serializable
@SerialName("tool_call")
private class ToolCallPartForm(
    val id: ToolCallId,
    val name: String,
    val arguments: String,
    val providerData: Map<Dialect, JsonObject>? = null,
) {
    constructor(part: ToolCallPart) : this(part.id, part.name, part.arguments, part.providerData.toForm())

    fun toPart(): ToolCallPart = ToolCallPart(id, name, arguments, providerData.toProviderData())
}

@Serializable
@SerialName("opaque")
private class OpaquePartForm(val dialect: Dialect, val kind: String, val json: JsonObject) {
    constructor(part: OpaquePart) : this(part.dialect, part.kind, part.json)

    fun toPart(): OpaquePart = OpaquePart(dialect, kind, json)
}

@Serializable
@SerialName("stored")
private class StoredMediaForm(val id: MediaId) {
    constructor(source: StoredMedia) : this(source.id)

    fun toSource(): StoredMedia = StoredMedia(id)
}

@Serializable
@SerialName("succeeded")
private data object SucceededForm

@Serializable
@SerialName("failed")
private data object FailedForm

@Serializable
@SerialName("cancelled")
private data object CancelledForm

@Serializable
@SerialName("not_run")
private class NotRunForm(val reason: NotRunReason, val approval: String? = null) {
    constructor(outcome: ToolOutcome.NotRun) : this(outcome.reason, outcome.approval)

    fun toOutcome(): ToolOutcome.NotRun = ToolOutcome.NotRun(reason, approval)
}
