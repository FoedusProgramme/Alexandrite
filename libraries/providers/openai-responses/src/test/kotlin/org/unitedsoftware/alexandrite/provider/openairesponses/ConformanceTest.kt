package org.unitedsoftware.alexandrite.provider.openairesponses

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesFixture
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesFlavor
import org.unitedsoftware.alexandrite.testkit.PROVIDER_CONTRACT

class ConformanceTest {
    @TestFactory
    fun `the standard API keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runTest { check.run(ResponsesFixture(ResponsesFlavor.STANDARD)) } }
    }
}
