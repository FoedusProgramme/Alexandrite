package org.foedusprogramme.alexandrite.sdk.chat

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi

/** The agent's settings of each chat, for other plugins to read. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChatSettings {
    /** The language the agent answers [chat] in, null when it is the default. */
    public suspend fun language(chat: ChatAddress): LanguageTag?
}
