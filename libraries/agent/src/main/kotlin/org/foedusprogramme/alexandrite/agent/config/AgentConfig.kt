package org.foedusprogramme.alexandrite.agent.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal class AgentConfig(
    /** How the agent calls itself, its id when null. */
    val name: String? = null,
    val description: String? = null,
    /** Persona text, before the [instructionFiles]. */
    val instructions: List<String> = emptyList(),
    val instructionFiles: List<InstructionFile> = emptyList(),
    /** `<endpoint>/<model>`. */
    val model: String? = null,
    val reasoning: String? = null,
    /** A BCP 47 tag. */
    val language: String? = null,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    /** The tool rounds of one turn. */
    val maxRounds: Int = 25,
    val toolTimeoutSeconds: Int = 120,
    val tools: ToolSelection = ToolSelection(),
    /** The agent's own directory. */
    val workspace: String? = null,
    /** The channel instances the agent serves, by `<type>:<name>`. */
    val channels: Map<String, AttachmentConfig> = emptyMap(),
)

@Serializable
internal class AttachmentConfig(
    /** Whether the agent serves the instance's chats that choose no other agent. */
    val default: Boolean = false,
    /** The agent's home chat on the instance, such as `123456`, or `123456#7` for a thread. */
    val home: String? = null,
)

/** Globs over dotted tool names, where `*` stands for any characters. */
@Serializable
internal class ToolSelection(
    val allow: List<String> = listOf("*"),
    /** Wins over [allow]. */
    val deny: List<String> = emptyList(),
    /** The tools offered on turns whose principal is no admin. */
    val forMembers: List<String> = emptyList(),
)

/** A persona file, written as its path or as `{"file": …, "when": […]}`. */
@Serializable(with = InstructionFileSerializer::class)
internal class InstructionFile(
    /** An absolute path, or one relative to the config file's directory. */
    val file: String,
    /** Ids of the chat kinds and turn kinds the file is for, every turn when empty. */
    val `when`: List<String> = emptyList(),
)

internal object InstructionFileSerializer : KSerializer<InstructionFile> {
    @Serializable
    private class Entry(val file: String, val `when`: List<String> = emptyList())

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: InstructionFile) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("An instruction file is JSON.")
        json.encodeSerializableValue(Entry.serializer(), Entry(value.file, value.`when`))
    }

    override fun deserialize(decoder: Decoder): InstructionFile {
        val json = decoder as? JsonDecoder ?: throw SerializationException("An instruction file is JSON.")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive if element.isString -> InstructionFile(element.content)

            is JsonObject -> json.json.decodeFromJsonElement(Entry.serializer(), element)
                .let { InstructionFile(it.file, it.`when`) }

            else -> throw SerializationException(
                "An instructionFiles entry is a path or an object with \"file\" and \"when\".",
            )
        }
    }
}
