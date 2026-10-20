package org.unitedsoftware.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ForwardOrigin
import org.unitedsoftware.alexandrite.sdk.chat.Quote
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.requireId
import java.time.Instant

/** Where the input of a [UserEntry] came from. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface UserOrigin {
    /** A message from the chat. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class FromChat(
        public val sender: ChatUser,
        public val message: ChannelMessageRef,
        public val receivedAt: Instant,
        /** Null when the message is not forwarded. */
        public val forwarded: ForwardOrigin?,
        /** The message that this one replies to, null when it replies to none. */
        public val quote: Quote?,
    ) : UserOrigin

    /** A turn that a plugin started, which carries no user's authority. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Initiated(
        /** The id of the plugin that started the turn. */
        public val plugin: String,
        public val kind: TurnKind,
    ) : UserOrigin {
        init {
            requireId(plugin, "plugin id")
        }
    }

    /** An entry that Alexandrite wrote itself, which carries no user's words or authority. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class SystemWritten(
        /** What wrote the entry. */
        public val source: String,
    ) : UserOrigin {
        init {
            require(source.isNotBlank()) { "The source of a system-written entry is not blank." }
        }
    }

    /** An origin of a type this version does not know, as stored. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Unknown(
        public val type: String,
        /** The stored object, its type included. */
        public val json: JsonObject,
    ) : UserOrigin
}
