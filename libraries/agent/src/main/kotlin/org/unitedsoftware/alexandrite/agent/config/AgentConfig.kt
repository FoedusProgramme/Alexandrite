package org.unitedsoftware.alexandrite.agent.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNames
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
    /** `<endpoint>/<model>`, read as the one item of [models]. */
    val model: String? = null,
    /** Equal models, each `<endpoint>/<model>`, the first the default. */
    val models: List<String> = emptyList(),
    val reasoning: String? = null,
    /** A BCP 47 tag. */
    val language: String? = null,
    /** The zone the agent tells times in, such as `Asia/Shanghai`, the host's when null. */
    val zone: String? = null,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    /** The tool rounds of one turn, unlimited when null. */
    val maxRounds: Int? = null,
    /** The input and output tokens of one turn's model calls together, unlimited when null. */
    val maxTurnTokens: Long? = null,
    /** How long a tool call may run in the foreground and the background together, unless its tool declares longer. */
    val toolTimeoutSeconds: Int = 1800,
    /** How long a tool call runs before it moves to the background. */
    val foregroundSeconds: Int = DEFAULT_FOREGROUND_SECONDS,
    val tools: ToolSelection = ToolSelection(),
    val permission: PermissionConfig = PermissionConfig(),
    val compaction: CompactionConfig = CompactionConfig(),
    /** The agent's own directory. */
    val workspace: String? = null,
    /** The channel instances the agent serves, by `<type>:<name>`. */
    val channels: Map<String, AttachmentConfig> = emptyMap(),
    /** Groups of chat addresses that share one conversation, kept at the first chat of each. */
    val linkedChats: List<List<String>> = emptyList(),
    val budget: BudgetConfig = BudgetConfig(),
)

/** The agent's budgets per period in its zone. */
@Serializable
internal class BudgetConfig(val daily: BudgetLimit? = null, val monthly: BudgetLimit? = null)

/** What one period may take: input and output tokens together, and US dollars. */
@Serializable
internal class BudgetLimit(val tokens: Long? = null, val money: Double? = null)

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
    /** The tools that a turn whose principal is no operator may call. */
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("forMembers")
    val forGuests: List<String> = emptyList(),
)

@Serializable
internal class PermissionConfig(
    /** The permission mode of the chats that set none. */
    val mode: String? = null,
    /** The calls that run without asking on an operator's turn, each `tool` or `tool(argv prefix)`. */
    val allow: List<String> = emptyList(),
    /** The calls that never run, written like [allow]. */
    val deny: List<String> = emptyList(),
    /** The calls whose output does not taint the conversation, written like [allow] or as a glob of tool names. */
    val trust: List<String> = emptyList(),
    /** `<endpoint>/<model>` of the judge in auto mode, the agent's model when null. */
    val judge: String? = null,
    val judgeTimeoutSeconds: Int = 20,
    /** The most escaped characters that the judge is shown of a call, and of the operator's words. */
    val judgeMaxChars: Int = 32_000,
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

/** How long a tool call of an agent that sets nothing runs before it moves to the background. */
internal const val DEFAULT_FOREGROUND_SECONDS = 300
