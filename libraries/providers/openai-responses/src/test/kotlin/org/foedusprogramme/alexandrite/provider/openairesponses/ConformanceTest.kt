package org.foedusprogramme.alexandrite.provider.openairesponses

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.provider.common.responses.ResponsesFixture
import org.foedusprogramme.alexandrite.provider.common.responses.ResponsesFlavor
import org.foedusprogramme.alexandrite.testkit.PROVIDER_CONTRACT
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ConformanceTest {
    @TestFactory
    fun `the standard API keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runTest { check.run(ResponsesFixture(ResponsesFlavor.STANDARD)) } }
    }
}
