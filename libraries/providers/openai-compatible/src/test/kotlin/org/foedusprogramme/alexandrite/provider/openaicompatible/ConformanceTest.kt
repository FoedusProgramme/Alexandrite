package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.testkit.PROVIDER_CONTRACT
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ConformanceTest {
    @TestFactory
    fun `every profile keeps the provider contract`(): List<DynamicContainer> = Profile.entries.map { profile ->
        DynamicContainer.dynamicContainer(
            profile.id,
            PROVIDER_CONTRACT.map { check ->
                DynamicTest.dynamicTest(check.name) { runBlocking { check.run(ChatFixture(profile)) } }
            },
        )
    }
}
