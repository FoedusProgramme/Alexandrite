package org.foedusprogramme.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId

/** A piece of an entry's content. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface Part

/** A part that a [UserEntry] may hold. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface UserPart : Part

/** A part that an [AssistantEntry] may hold. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface AssistantPart : Part

/** A part that a tool result may hold. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ToolOutputPart : Part

@OptIn(InternalAlexandriteApi::class)
@Poko
public class TextPart(public val text: String) :
    UserPart,
    AssistantPart,
    ToolOutputPart

/** Text the agent wrote for the model, such as the sender and time of a message. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class ContextPart(
    /** What wrote the text. */
    public val source: String,
    public val text: String,
) : UserPart

@OptIn(InternalAlexandriteApi::class)
@Poko
public class MediaPart(
    public val kind: MediaKind,
    /** The IANA media type, such as `image/png`. */
    public val mediaType: String,
    public val source: MediaSource,
    /** The file name, null when the media has none. */
    public val name: String? = null,
    /** In pixels, null when unknown. */
    public val width: Int? = null,
    /** In pixels, null when unknown. */
    public val height: Int? = null,
) : UserPart,
    ToolOutputPart

/** The model's reasoning before its answer. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class ReasoningPart(
    /** Null when the provider does not show the reasoning. */
    public val text: String?,
    /** Null when the provider gives no summary. */
    public val summary: String?,
    /** Null when the model needs nothing to continue the reasoning. */
    public val seal: ReasoningSeal?,
) : AssistantPart

/** A call of a tool by the model. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class ToolCallPart(
    public val id: ToolCallId,
    /** The tool's name, mapped back from its wire form where it names a tool. */
    public val name: String,
    /** The arguments as the model wrote them, which may be invalid JSON. */
    public val arguments: String,
    public val providerData: ProviderData = ProviderData.EMPTY,
) : AssistantPart {
    /** The arguments as a JSON object, empty when they are blank and null when they are no object. */
    public fun parseArguments(): JsonObject? {
        if (arguments.isBlank()) return JsonObject(emptyMap())
        return try {
            Json.parseToJsonElement(arguments) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
    }
}

/** Output of one wire dialect that has no part of its own, replayed only to that dialect. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class OpaquePart(
    public val dialect: Dialect,
    /** The dialect's own name for the output. */
    public val kind: String,
    public val json: JsonObject,
) : AssistantPart

/** A part of a type this version does not know, which no model is sent. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class UnknownPart(
    public val type: String,
    /** The stored object, its type included. */
    public val json: JsonObject,
) : UserPart,
    AssistantPart,
    ToolOutputPart
