package org.unitedsoftware.alexandrite.provider.anthropiccompatible

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesFixture
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesFlavor
import org.unitedsoftware.alexandrite.testkit.PROVIDER_CONTRACT

class ConformanceTest {
    @TestFactory
    fun `the standard API keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runBlocking { check.run(MessagesFixture(MessagesFlavor.STANDARD)) } }
    }
}
