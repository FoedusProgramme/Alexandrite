package org.unitedsoftware.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.StringFormSerializer

/** The type of a channel, declared by its plugin, such as `telegram`. */
@JvmInline
@Serializable
public value class ChannelType(public val value: String) {
    init {
        requireId(value, "channel type")
    }

    override fun toString(): String = value
}

/** A channel instance, printed `<type>:<name>`. */
@Serializable(with = ChannelInstanceIdSerializer::class)
@Poko
public class ChannelInstanceId(
    public val type: ChannelType,
    /** The operator's config key of the instance, unique within its [type]. */
    public val name: String,
) {
    init {
        requireId(name, "channel instance name")
    }

    override fun toString(): String = "$type:$name"

    public companion object {
        public fun parse(text: String): ChannelInstanceId =
            parseOrNull(text) ?: throw IllegalArgumentException("Malformed channel instance id '$text'.")

        public fun parseOrNull(text: String): ChannelInstanceId? {
            val type = text.substringBefore(':', "")
            val name = text.substringAfter(':', "")
            return if (isId(type) && isId(name)) ChannelInstanceId(ChannelType(type), name) else null
        }
    }
}

internal object ChannelInstanceIdSerializer : StringFormSerializer<ChannelInstanceId>(
    "org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId",
    "channel instance id",
    ChannelInstanceId::parseOrNull,
)
