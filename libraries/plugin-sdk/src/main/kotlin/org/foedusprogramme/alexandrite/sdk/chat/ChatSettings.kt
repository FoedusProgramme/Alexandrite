package org.foedusprogramme.alexandrite.sdk.chat

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import java.time.ZoneId

/** The agent's settings of each chat, for other plugins to read. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChatSettings {
    /** The language the agent answers [chat] in, null when the host's default language applies. */
    public suspend fun language(chat: ChatAddress): LanguageTag?

    /** The zone the agent tells [chat]'s times in, null when the host's zone applies. */
    public suspend fun zone(chat: ChatAddress): ZoneId? = null
}
