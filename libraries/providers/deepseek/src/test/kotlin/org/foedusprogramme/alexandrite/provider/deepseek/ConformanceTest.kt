package org.foedusprogramme.alexandrite.provider.deepseek

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.testkit.PROVIDER_CONTRACT
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ConformanceTest {
    @TestFactory
    fun `DeepSeek keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runBlocking { check.run(DeepSeekFixture()) } }
    }
}
