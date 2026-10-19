package org.unitedsoftware.alexandrite.provider.common.chat

import org.unitedsoftware.alexandrite.provider.common.EndpointSettings

/** What an endpoint of the Chat Completions API does beyond its [EndpointSettings]. */
public class ChatApiSettings(
    /** Whether requests carry the agent's cache key. */
    public val promptCacheKey: Boolean = false,
)
