package org.unitedsoftware.alexandrite.provider.openaicompatible

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFixture
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFlavor
import org.unitedsoftware.alexandrite.testkit.PROVIDER_CONTRACT

class ConformanceTest {
    @TestFactory
    fun `the standard API keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runTest { check.run(ChatFixture(ChatFlavor.STANDARD)) } }
    }
}
