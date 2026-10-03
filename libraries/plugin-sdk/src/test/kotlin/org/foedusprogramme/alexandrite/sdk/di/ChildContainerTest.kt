package org.foedusprogramme.alexandrite.sdk.di

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.AMBIGUOUS
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.CLOSED
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.EXTRA_SCOPE
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.MISSING
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.NESTED_CHILD
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.UNKNOWN_PLUGIN
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.UNLISTED_PLUGIN
import org.foedusprogramme.alexandrite.sdk.di.Scope.CHANNEL_INSTANCE
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ChildContainerTest {
    private val events = Events()
    private val channelName = key<String>("channel.name")
    private val nameOfChannel = Dependency(channelName, DependencyKind.INSTANCE, "name")

    private fun nameBinding(name: String) =
        instanceBinding(channelName, name, "runtime", "channel config", scope = CHANNEL_INSTANCE)

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

        assertEquals(listOf(MISSING), error.problems.map { it.kind })
        assertEquals(
            "Cannot build channel instance container 'tg' (1 problem):\n" +
                "- Missing binding: nothing binds $channelName, which c (plugin test) needs for parameter 'name'. " +
                "Loaded plugins: test.",
            error.message,
        )
    }

    @Test
    fun `an extra binding that collides with a parent binding is ambiguous`() {
        val root = build(service("a"))

        val error = assertFailsWith<DiException> {
            root.child("tg", setOf("test"), listOf(service("a", plugin = "extra", scope = CHANNEL_INSTANCE)))
        }

        assertEquals(listOf(AMBIGUOUS), error.problems.map { it.kind })
        assertContains(error.message!!, "a (plugin test) and a (plugin extra)")
    }

    @Test
    fun `a child rejects an extra binding that is not channel-instance-scoped`() {
        val error = assertFailsWith<DiException> { build().child("tg", setOf("test"), listOf(service("x"))) }

        assertEquals(listOf(EXTRA_SCOPE to "test"), error.problems.map { it.kind to it.plugin })
        assertContains(
            error.message!!,
            "x (plugin test) is added to a channel instance container, but is not channel-instance-scoped.",
        )
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
    fun `a parent closed while a child is created closes the child and fails`() {
        lateinit var root: Container
        val closer = binding(svc("closer"), "test", "closer", scope = CHANNEL_INSTANCE) {
            root.close()
            Service("closer", events, emptyMap(), failStart = false, failClose = false)
        }
        root = build(service("a", events = events), closer)

        val error = assertFailsWith<DiException> { root.child("tg", setOf("test")) }

        assertEquals(listOf(CLOSED), error.problems.map { it.kind })
        assertEquals("Cannot create channel instance container 'tg': container 'root' is closed.", error.message)
        assertEquals(listOf("close a", "close closer"), events.starting("close"))
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

        assertContains(
            error.message!!,
            "is channel-instance-scoped, bound by c (plugin test), so container 'root' does not create it.",
        )
    }

    @Test
    fun `a channel instance container cannot create children`() {
        val child = build().child("tg", setOf("test"))

        val error = assertFailsWith<DiException> { child.child("nested", setOf("test")) }

        assertEquals(listOf(NESTED_CHILD), error.problems.map { it.kind })
    }

    // Validation.

    @Test
    fun `validateChild reports the problems of a child without creating anything`() {
        val root = build(
            service("a", events = events),
            service("c", dep("a"), nameOfChannel, scope = CHANNEL_INSTANCE, events = events),
        )

        val problems = root.validateChild(setOf("test", "tset"), listOf(service("x")))

        assertEquals(
            listOf(UNKNOWN_PLUGIN to null, EXTRA_SCOPE to "test", MISSING to "test"),
            problems.map { it.kind to it.plugin },
        )
        assertEquals(emptyList(), root.validateChild(setOf("test"), listOf(nameBinding("tg"))))
        assertEquals(listOf("create a"), events.all())
    }

    @Test
    fun `validateChild is refused inside a channel instance container`() {
        val child = build().child("tg", setOf("test"))

        val error = assertFailsWith<DiException> { child.validateChild(setOf("test")) }

        assertEquals(listOf(NESTED_CHILD), error.problems.map { it.kind })
    }

    // Plugins.

    private fun channels(
        telegram: List<Binding<*>> = listOf(channel("bot", dep("core"), plugin = "telegram")),
        discord: List<Binding<*>> = listOf(channel("guild", dep("core"), plugin = "discord")),
    ): Container = Container.build(
        listOf(
            plugin("core", service("core", plugin = "core", events = events)),
            plugin("telegram", *telegram.toTypedArray()),
            plugin("discord", *discord.toTypedArray()),
        ),
    )

    private fun channel(name: String, vararg dependencies: Dependency, plugin: String) =
        service(name, *dependencies, scope = CHANNEL_INSTANCE, plugin = plugin, events = events)

    @Test
    fun `a child creates only the channel bindings of its plugins`() {
        val root = channels()

        val telegram = root.child("tg", setOf("telegram"))

        assertEquals(listOf("create core", "create bot"), events.all())
        assertEquals("bot", telegram.get(svc("bot")).name)
        root.child("both", setOf("telegram", "discord"))
        assertEquals(listOf("create core", "create bot", "create guild", "create bot"), events.all())
    }

    @Test
    fun `a child validates only the channel bindings of its plugins`() {
        val root = channels(discord = listOf(channel("guild", dep("webhook"), plugin = "discord")))

        root.child("tg", setOf("telegram"))
        val error = assertFailsWith<DiException> { root.child("dc", setOf("discord")) }

        assertEquals(listOf(MISSING), error.problems.map { it.kind })
        assertContains(error.message!!, "which guild (plugin discord) needs for parameter 'webhook'")
    }

    @Test
    fun `a channel binding that needs a channel binding of an unlisted plugin fails naming both plugins`() {
        val serviceType = Service::class.qualifiedName
        val root = channels(
            telegram = listOf(
                channel("bot", dep("guild"), plugin = "telegram"),
                channel("relay", dep("guild", DependencyKind.OPTIONAL), plugin = "telegram"),
            ),
        )

        val error = assertFailsWith<DiException> { root.child("tg", setOf("telegram")) }

        assertEquals(
            """
            Cannot build channel instance container 'tg' (2 problems):
            - Unlisted plugin: plugin 'telegram' needs @Named("guild") $serviceType from plugin 'discord': bot (plugin telegram) injects it as parameter 'guild' and only guild (plugin discord) binds it, but the channel instance container is not for plugin 'discord'.
            - Unlisted plugin: plugin 'telegram' needs @Named("guild") $serviceType from plugin 'discord': relay (plugin telegram) injects it as parameter 'guild' and only guild (plugin discord) binds it, but the channel instance container is not for plugin 'discord'.
            """.trimIndent(),
            error.message,
        )
        assertEquals(
            List(2) { Triple(UNLISTED_PLUGIN, "telegram", svc("guild")) },
            error.problems.map { Triple(it.kind, it.plugin, it.key) },
        )
        val both = root.child("both", setOf("telegram", "discord"))
        assertSame(both.get(svc("guild")), both.get(svc("bot")).dependency("guild"))
    }

    @Test
    fun `a child refuses a key of a plugin it is not for`() {
        val telegram = channels().child("tg", setOf("telegram"))

        val error = assertFailsWith<DiException> { telegram.get(svc("guild")) }

        assertEquals(UNLISTED_PLUGIN, error.problems.single().kind)
        assertContains(
            error.message!!,
            "is only bound in plugin 'discord', by guild (plugin discord), which channel instance container 'tg' " +
                "is not for.",
        )
    }

    @Test
    fun `a child for a plugin that is not loaded fails`() {
        val error = assertFailsWith<DiException> { channels().child("tg", setOf("telegram", "telegarm")) }

        assertEquals(
            "Cannot build channel instance container 'tg' (1 problem):\n" +
                "- Unknown plugin: 'telegarm' is not loaded. Loaded plugins: core, discord, telegram.",
            error.message,
        )
        assertEquals(UNKNOWN_PLUGIN, error.problems.single().kind)
    }

    @Test
    fun `a child's getAll mixes singleton and channel contributions of its plugins in order, extras last`() {
        val tools = svc("tools")
        val root = Container.build(
            listOf(
                plugin("beta", service("b1", key = tools, multi = true, scope = CHANNEL_INSTANCE, plugin = "beta")),
                plugin(
                    "alpha",
                    service("a1", key = tools, multi = true, plugin = "alpha"),
                    service("a2", key = tools, multi = true, scope = CHANNEL_INSTANCE, plugin = "alpha"),
                ),
            ),
        )
        val extra = service("extra", key = tools, multi = true, scope = CHANNEL_INSTANCE)

        val child = root.child("tg", setOf("alpha", "beta"), listOf(extra))

        assertEquals(listOf("a1", "a2", "b1", "extra"), child.getAll(tools).map { it.name })
        assertEquals(listOf("a1", "a2"), root.child("alpha", setOf("alpha")).getAll(tools).map { it.name })
        assertSame(child.getAll(tools).first(), root.child("beta", setOf("beta")).getAll(tools).first())
        assertFailsWith<DiException> { root.getAll(tools) }
    }

    @Test
    fun `channel bindings of one key in two plugins are ambiguous only in a child of both`() {
        val driver = svc("driver")
        val root = channels(
            telegram = listOf(service("bot", key = driver, scope = CHANNEL_INSTANCE, plugin = "telegram")),
            discord = listOf(service("guild", key = driver, scope = CHANNEL_INSTANCE, plugin = "discord")),
        )

        assertEquals("bot", root.child("tg", setOf("telegram")).get(driver).name)
        assertEquals("guild", root.child("dc", setOf("discord")).get(driver).name)
        val error = assertFailsWith<DiException> { root.child("both", setOf("telegram", "discord")) }
        assertEquals(listOf(AMBIGUOUS), error.problems.map { it.kind })
        assertContains(error.message!!, "guild (plugin discord) and bot (plugin telegram)")
    }

    @Test
    fun `the root rejects a key bound twice in one plugin or by a singleton and a channel binding`() {
        val driver = svc("driver")
        fun channel(name: String, plugin: String) =
            service(name, key = driver, scope = CHANNEL_INSTANCE, plugin = plugin)

        val twice = assertFailsWith<DiException> {
            channels(
                telegram = listOf(channel("bot", "telegram"), channel("relay", "telegram")),
                discord = listOf(channel("guild", "discord")),
            )
        }
        val mixed = assertFailsWith<DiException> {
            channels(
                telegram = listOf(channel("bot", "telegram")),
                discord = listOf(service("hub", key = driver, plugin = "discord")),
            )
        }

        assertEquals(listOf(AMBIGUOUS), twice.problems.map { it.kind })
        assertContains(twice.message!!, "is bound by bot (plugin telegram) and relay (plugin telegram).")
        assertEquals(listOf(AMBIGUOUS), mixed.problems.map { it.kind })
        assertContains(mixed.message!!, "is bound by hub (plugin discord) and bot (plugin telegram).")
    }
}
