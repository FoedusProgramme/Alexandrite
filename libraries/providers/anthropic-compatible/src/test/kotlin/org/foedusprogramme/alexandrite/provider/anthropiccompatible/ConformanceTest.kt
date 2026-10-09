package org.foedusprogramme.alexandrite.provider.anthropiccompatible

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.provider.common.messages.MessagesFixture
import org.foedusprogramme.alexandrite.provider.common.messages.MessagesFlavor
import org.foedusprogramme.alexandrite.testkit.PROVIDER_CONTRACT
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ConformanceTest {
    @TestFactory
    fun `the standard API keeps the provider contract`(): List<DynamicTest> = PROVIDER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runBlocking { check.run(MessagesFixture(MessagesFlavor.STANDARD)) } }
    }
}
