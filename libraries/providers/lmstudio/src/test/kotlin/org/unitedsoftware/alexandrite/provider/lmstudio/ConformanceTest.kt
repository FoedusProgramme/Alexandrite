package org.unitedsoftware.alexandrite.provider.lmstudio

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.testkit.PROVIDER_CONTRACT

class ConformanceTest {
    @TestFactory
    fun `LM Studio keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runBlocking { check.run(LmStudioFixture()) } }
    }
}
