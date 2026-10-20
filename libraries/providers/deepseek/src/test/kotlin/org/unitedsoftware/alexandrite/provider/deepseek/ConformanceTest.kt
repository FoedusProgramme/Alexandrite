package org.unitedsoftware.alexandrite.provider.deepseek

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.testkit.PROVIDER_CONTRACT

class ConformanceTest {
    @TestFactory
    fun `DeepSeek keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runBlocking { check.run(DeepSeekFixture()) } }
    }
}
