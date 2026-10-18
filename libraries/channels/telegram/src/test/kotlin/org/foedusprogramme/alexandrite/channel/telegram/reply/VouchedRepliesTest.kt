package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.channel.telegram.intake.TestInstance
import org.foedusprogramme.alexandrite.testkit.TestRecords
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class VouchedRepliesTest {
    @Test
    fun `the latest replies are kept across a restart and the oldest past the bound are forgotten`() = runTest {
        val records = TestRecords()
        val first = VouchedReplies(TestInstance(backgroundScope), records.of("alexandrite-channel-telegram"))
        first.onStart()

        first.note(GROUP, (1L..VouchedReplies.MAX_KEPT + 2L).toList())
        first.onDrain()
        val second = VouchedReplies(TestInstance(backgroundScope), records.of("alexandrite-channel-telegram"))
        second.onStart()

        for (replies in listOf(first, second)) {
            assertEquals(
                listOf(false, false, true, true),
                listOf(1L, 2L, 3L, VouchedReplies.MAX_KEPT + 2L).map { replies.contains(GROUP, it) },
            )
        }
        assertEquals(false, second.contains(DIRECT, 3))
    }
}
