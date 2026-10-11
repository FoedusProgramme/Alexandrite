package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFixture
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFlavor
import org.foedusprogramme.alexandrite.testkit.PROVIDER_CONTRACT
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ConformanceTest {
    @TestFactory
    fun `the standard API keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runTest { check.run(ChatFixture(ChatFlavor.STANDARD)) } }
    }
}
