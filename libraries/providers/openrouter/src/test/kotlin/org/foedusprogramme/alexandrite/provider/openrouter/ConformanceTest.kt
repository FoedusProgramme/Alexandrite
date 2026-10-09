package org.foedusprogramme.alexandrite.provider.openrouter

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.testkit.PROVIDER_CONTRACT
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ConformanceTest {
    @TestFactory
    fun `OpenRouter keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runBlocking { check.run(OpenRouterFixture()) } }
    }
}
