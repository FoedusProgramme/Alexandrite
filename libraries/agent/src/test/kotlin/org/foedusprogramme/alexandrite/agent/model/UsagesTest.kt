package org.foedusprogramme.alexandrite.agent.model

import org.foedusprogramme.alexandrite.sdk.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals

class UsagesTest {
    @Test
    fun `two calls' usage sums the counts both give and keeps the later call's context`() {
        val first = Usage.builder()
            .inputTokens(100)
            .cacheReadTokens(40)
            .outputTokens(10)
            .reasoningTokens(5)
            .contextTokens(100)
            .build()
        val second = Usage.builder().inputTokens(150).outputTokens(20).contextTokens(150).requests(2).build()

        assertEquals(
            Usage.builder().inputTokens(250).outputTokens(30).contextTokens(150).requests(3).build(),
            first + second,
        )
    }
}
