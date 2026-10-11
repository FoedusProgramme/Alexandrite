package org.foedusprogramme.alexandrite.sdk

import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValueTypesTest {
    private data class Sample(val name: String)

    @Test
    fun `stop requests are built by kind`() {
        assertEquals(
            listOf(StopKind.SHUTDOWN, StopKind.RESTART, StopKind.FAILURE).map { StopRequest(it, "why") },
            listOf(StopRequest.shutdown("why"), StopRequest.restart("why"), StopRequest.failure("why")),
        )
    }

    @Test
    fun `no public class of the SDK has copy or component functions`() {
        val public = sdkClasses.filter { Modifier.isPublic(it.modifiers) }

        assertTrue(listOf(Key::class.java, TurnInfo::class.java, FloorCheck.Allowed::class.java).all { it in public })
        assertTrue(Sample::class.java.copiesOrComponents())
        assertEquals(emptyList(), public.filter { it.copiesOrComponents() }.map { it.name })
    }

    private fun Class<*>.copiesOrComponents(): Boolean = methods.any { COPY_OR_COMPONENT.matches(it.name) }

    private companion object {
        val COPY_OR_COMPONENT = Regex("(copy|component\\d+)(-.*)?")
    }
}
