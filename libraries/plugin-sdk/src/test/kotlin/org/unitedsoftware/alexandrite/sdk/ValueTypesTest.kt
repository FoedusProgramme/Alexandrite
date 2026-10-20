package org.unitedsoftware.alexandrite.sdk

import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinkGroup
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValueTypesTest {
    private data class Sample(val name: String)

    @Test
    fun `values keep copies of the collections they are built from`() {
        val chat = ChatAddress(ChannelInstanceId(ChannelType("telegram"), "work"), "1")
        val paths = mutableListOf(Path.of("/srv/work"))
        val members = mutableListOf(ChatAddress(chat.instance, "2"))
        val allowed = mutableListOf("fs.*")
        val refs = mutableListOf(ChannelMessageRef(chat, "1"))
        val boundary = Boundary(paths)
        val group = ChatLinkGroup(AgentId.MAIN, chat, members)
        val profile = AgentProfile.builder(AgentId("coder"), "Coder", "Codes.", "Be brief.").toolsAllow(allowed).build()
        val delivered = Delivery.Delivered(refs)

        listOf(paths, members, allowed, refs).forEach { it.clear() }

        assertEquals(listOf(Path.of("/srv/work")), boundary.writable)
        assertEquals(listOf(ChatAddress(chat.instance, "2")), group.members)
        assertEquals(listOf("fs.*"), profile.toolsAllow)
        assertEquals(listOf(ChannelMessageRef(chat, "1")), delivered.messages)
    }

    @Test
    fun `no public class of the SDK has copy or component functions`() {
        val public = sdkClasses.filter { Modifier.isPublic(it.modifiers) }

        assertTrue(listOf(Key::class.java, TurnInfo::class.java, FloorCheck.Allowed::class.java).all { it in public })
        assertTrue(Sample::class.java.copiesOrComponents())
        assertEquals(emptyList(), public.filter { it.copiesOrComponents() }.map { it.name })
    }

    @Test
    fun `no class of the SDK keeps a deprecated member`() {
        val deprecated = sdkClasses.flatMap { type ->
            (type.declaredConstructors.toList() + type.declaredMethods + type.declaredFields)
                .filter { it.isAnnotationPresent(Deprecated::class.java) }
                .map { "${type.name}.${it.name}" }
        }

        assertEquals(emptyList(), deprecated)
    }

    private fun Class<*>.copiesOrComponents(): Boolean = methods.any { COPY_OR_COMPONENT.matches(it.name) }

    private companion object {
        val COPY_OR_COMPONENT = Regex("(copy|component\\d+)(-.*)?")
    }
}
