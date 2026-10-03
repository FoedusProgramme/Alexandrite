package org.foedusprogramme.alexandrite.sdk.di

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.di.Scope.CHANNEL_INSTANCE
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChildContainerTest {
    private val events = Events()
    private val channelName = key<String>("channel.name")
    private val nameOfChannel = Dependency(channelName, DependencyKind.INSTANCE, "name")

    private fun nameBinding(name: String) = instanceBinding(channelName, name, "runtime", "channel config")

    @Test
    fun `a child sees the parent's singletons and its own bindings`() {
        val root = build(service("a"), service("c", dep("a"), nameOfChannel, scope = CHANNEL_INSTANCE))

        val child = root.child("tg", setOf("test"), listOf(nameBinding("tg")))

        val c = child.get(svc("c"))
        assertSame(root.get(svc("a")), c.dependency("a"))
        assertEquals("tg", c.injected["name"])
        assertSame(root.get(svc("a")), child.get(svc("a")))
    }

    @Test
    fun `a child creates channel bindings in dependency order`() {
        val root = build(
            service("a", events = events),
            service("d", dep("c"), scope = CHANNEL_INSTANCE, events = events),
            service("c", dep("a"), dep("e"), scope = CHANNEL_INSTANCE, events = events),
            service("e", scope = CHANNEL_INSTANCE, events = events),
        )

        root.child("tg", setOf("test"))

        assertEquals(listOf("create a", "create e", "create c", "create d"), events.all())
    }

    @Test
    fun `two children get distinct channel instances`() {
        val root = build(service("a"), service("c", dep("a"), scope = CHANNEL_INSTANCE))

        val first = root.child("first", setOf("test")).get(svc("c"))
        val second = root.child("second", setOf("test")).get(svc("c"))

        assertNotSame(first, second)
        assertSame(first.dependency("a"), second.dependency("a"))
    }

    @Test
    fun `a child validates its graph when created`() {
        val root = build(service("c", nameOfChannel, scope = CHANNEL_INSTANCE))

        val error = assertFailsWith<DiException> { root.child("tg", setOf("test")) }

        assertTrue(error.message!!.startsWith("Cannot build channel instance container 'tg' (1 problem):"))
        assertTrue("which c (module test) needs for parameter 'name'" in error.message!!)
        assertTrue("pass it to child()" in error.message!!)
        assertEquals(listOf(ProblemKind.MISSING), error.problems.map { it.kind })
    }

    @Test
    fun `an extra binding that collides with a parent binding is ambiguous`() {
        val root = build(service("a"))

        val error = assertFailsWith<DiException> {
            root.child("tg", setOf("test"), listOf(service("a", module = "extra")))
        }

        assertTrue("a (module test) and a (module extra)" in error.message!!)
        assertEquals(listOf(ProblemKind.AMBIGUOUS), error.problems.map { it.kind })
    }

    @Test
    fun `closing a child closes only its own instances`() {
        val root = build(service("a", events = events), service("c", scope = CHANNEL_INSTANCE, events = events))
        val child = root.child("tg", setOf("test"))

        child.close()

        assertEquals(listOf("close c"), events.starting("close"))
        assertEquals("a", root.get(svc("a")).name)
    }

    @Test
    fun `closing the parent closes live children first`() {
        val root = build(service("a", events = events), service("c", scope = CHANNEL_INSTANCE, events = events))
        root.child("first", setOf("test")).close()
        val live = root.child("second", setOf("test"))

        root.close()

        assertEquals(listOf("close c", "close c", "close a"), events.starting("close"))
        assertFailsWith<DiException> { live.get(svc("c")) }
    }

    @Test
    fun `a child starts only its own instances`() {
        val root = build(service("a", events = events), service("c", scope = CHANNEL_INSTANCE, events = events))

        runBlocking { root.child("tg", setOf("test")).start() }

        assertEquals(listOf("start c"), events.starting("start"))
    }

    @Test
    fun `the root container refuses channel-instance-scoped keys`() {
        val root = build(service("c", scope = CHANNEL_INSTANCE))

        val error = assertFailsWith<DiException> { root.get(svc("c")) }

        assertTrue("is channel-instance-scoped, bound by c (module test)" in error.message!!)
    }

    @Test
    fun `a channel instance container cannot create children`() {
        val child = build().child("tg", setOf("test"))

        assertFailsWith<DiException> { child.child("nested", setOf("test")) }
    }

    // Modules.

    private fun channels(
        telegram: List<Binding<*>> = listOf(channel("bot", dep("core"), module = "telegram")),
        discord: List<Binding<*>> = listOf(channel("guild", dep("core"), module = "discord")),
    ): Container = Container.build(
        listOf(
            index("core", service("core", module = "core", events = events)),
            index("telegram", *telegram.toTypedArray()),
            index("discord", *discord.toTypedArray()),
        ),
    )

    private fun channel(name: String, vararg dependencies: Dependency, module: String) =
        service(name, *dependencies, scope = CHANNEL_INSTANCE, module = module, events = events)

    @Test
    fun `a child creates only the channel bindings of its modules`() {
        val root = channels()

        val telegram = root.child("tg", setOf("telegram"))

        assertEquals(listOf("create core", "create bot"), events.all())
        assertEquals("bot", telegram.get(svc("bot")).name)
        root.child("both", setOf("telegram", "discord"))
        assertEquals(listOf("create core", "create bot", "create guild", "create bot"), events.all())
    }

    @Test
    fun `a child validates only the channel bindings of its modules`() {
        val root = channels(discord = listOf(channel("guild", dep("webhook"), module = "discord")))

        root.child("tg", setOf("telegram"))
        val error = assertFailsWith<DiException> { root.child("dc", setOf("discord")) }

        assertEquals(listOf(ProblemKind.MISSING), error.problems.map { it.kind })
        assertContains(error.message!!, "which guild (module discord) needs for parameter 'webhook'")
    }

    @Test
    fun `a channel binding that needs a channel binding of an unlisted module fails naming both modules`() {
        val serviceType = Service::class.qualifiedName
        val root = channels(
            telegram = listOf(
                channel("bot", dep("guild"), module = "telegram"),
                channel("relay", dep("guild", DependencyKind.OPTIONAL), module = "telegram"),
            ),
        )

        val error = assertFailsWith<DiException> { root.child("tg", setOf("telegram")) }

        assertEquals(
            """
            Cannot build channel instance container 'tg' (2 problems):
            - Unlisted module: module 'telegram' needs @Named("guild") $serviceType from module 'discord': bot (module telegram) injects it as parameter 'guild' and only guild (module discord) binds it, but channel instance container 'tg' was not created for module 'discord'. List 'discord' in the modules passed to child() or drop the dependency.
            - Unlisted module: module 'telegram' needs @Named("guild") $serviceType from module 'discord': relay (module telegram) injects it as parameter 'guild' and only guild (module discord) binds it, but channel instance container 'tg' was not created for module 'discord'. List 'discord' in the modules passed to child() or drop the dependency.
            """.trimIndent(),
            error.message,
        )
        assertEquals(
            List(2) { Triple(ProblemKind.UNLISTED_MODULE, "telegram", svc("guild")) },
            error.problems.map { Triple(it.kind, it.module, it.key) },
        )
        val both = root.child("both", setOf("telegram", "discord"))
        assertSame(both.get(svc("guild")), both.get(svc("bot")).dependency("guild"))
    }

    @Test
    fun `a child refuses a key of a module it was not created for`() {
        val telegram = channels().child("tg", setOf("telegram"))

        val error = assertFailsWith<DiException> { telegram.get(svc("guild")) }

        assertEquals(ProblemKind.UNLISTED_MODULE, error.problems.single().kind)
        assertContains(error.message!!, "is only bound in module 'discord', by guild (module discord)")
    }

    @Test
    fun `a child for a module that is not loaded fails`() {
        val error = assertFailsWith<DiException> { channels().child("tg", setOf("telegram", "telegarm")) }

        assertEquals(
            "Cannot build channel instance container 'tg' (1 problem):\n" +
                "- Unknown module: channel instance container 'tg' was created for module 'telegarm', " +
                "which is not loaded. Loaded modules: core, discord, telegram.",
            error.message,
        )
        assertEquals(ProblemKind.UNKNOWN_MODULE, error.problems.single().kind)
    }

    @Test
    fun `a child's getAll mixes singleton and channel contributions of its modules in order`() {
        val tools = svc("tools")
        val root = Container.build(
            listOf(
                index("beta", service("b1", key = tools, multi = true, scope = CHANNEL_INSTANCE, module = "beta")),
                index(
                    "alpha",
                    service("a1", key = tools, multi = true, module = "alpha"),
                    service("a2", key = tools, multi = true, scope = CHANNEL_INSTANCE, module = "alpha"),
                ),
            ),
        )

        val child = root.child("tg", setOf("alpha", "beta"), listOf(service("extra", key = tools, multi = true)))

        assertEquals(listOf("a1", "a2", "b1", "extra"), child.getAll(tools).map { it.name })
        assertEquals(listOf("a1", "a2"), root.child("alpha", setOf("alpha")).getAll(tools).map { it.name })
        assertSame(child.getAll(tools).first(), root.child("beta", setOf("beta")).getAll(tools).first())
        assertFailsWith<DiException> { root.getAll(tools) }
    }
}
