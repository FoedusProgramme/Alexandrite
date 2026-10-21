package org.unitedsoftware.alexandrite.internal.manual

import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManualTest {
    @TempDir
    lateinit var directory: Path

    private val manual = Manual(
        listOf(
            page(
                "core",
                "permissions",
                """
                # Permissions

                How calls are allowed.

                ## Modes

                Each chat has a permission mode: plan, ask, auto or bypass.

                ## Approvals

                A person allows a gated call by a button.
                """,
            ),
            page(
                "mcp",
                "mcp",
                """
                # MCP servers

                How to add an MCP server.

                ## Adding a server

                A server runs as a command or is reached by its url; servers are restarted when they fail.
                """,
            ),
            page("core", "overview", "# Overview\n\nWhat runs where, and which servers it reaches.\n"),
            page("other", "overview", "# Other\n\nAnother plugin's overview.\n"),
        ),
    )

    @Test
    fun `a topic finds its page alone or with its plugin, and an ambiguous or unknown one finds none`() {
        assertEquals("core/permissions", assertIs<ManualLookup.Found>(manual.find("permissions")).page.name)
        assertEquals("other/overview", assertIs<ManualLookup.Found>(manual.find("other/overview")).page.name)
        assertEquals(
            listOf("core/overview", "other/overview"),
            assertIs<ManualLookup.Ambiguous>(manual.find("overview")).pages.map { it.name },
        )
        assertIs<ManualLookup.Unknown>(manual.find("nothing"))
        assertEquals(
            "core/overview — What runs where, and which servers it reaches.\n" +
                "core/permissions — How calls are allowed.\n" +
                "mcp/mcp — How to add an MCP server.\n" +
                "other/overview — Another plugin's overview.",
            manual.index(),
        )
    }

    @Test
    fun `a search forgives typos and word forms and ranks the sections that match every word first`() {
        val typo = manual.search("permision mode")
        assertEquals("core/permissions" to "Modes", typo.first().let { it.page.name to it.heading })

        val server = manual.search("mcp server")
        assertEquals("mcp/mcp", server.first().page.name)
        assertTrue(server.none { it.page.name == "other/overview" })

        assertEquals(emptyList(), manual.search("zebra"))
    }

    @Test
    fun `each plugin's pages are read from its own directory through its own class loader, not its parent's`() {
        val tree = directory.resolve("classes")
        write(tree.resolve("alexandrite/manual/alpha/guide.md"), "# Guide\n\nHow alpha works.\n")
        write(tree.resolve("alexandrite/manual/alpha/broken.md"), "No title here.\n")
        write(tree.resolve("alexandrite/manual/beta/forged.md"), "# Forged\n\nA page under another plugin's id.\n")
        val jar = directory.resolve("beta.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            for (name in listOf("alexandrite/", "alexandrite/manual/", "alexandrite/manual/beta/")) {
                out.putNextEntry(JarEntry(name))
            }
            out.putNextEntry(JarEntry("alexandrite/manual/beta/setup.md"))
            out.write("# Setup\n\nHow beta is set up.\n".toByteArray())
        }

        val read = URLClassLoader(arrayOf(tree.toUri().toURL()), null).use { alpha ->
            URLClassLoader(arrayOf(jar.toUri().toURL()), alpha).use { beta ->
                Manual.read(mapOf("alpha" to alpha, "beta" to beta))
            }
        }

        assertEquals(listOf("alpha/guide", "beta/setup"), read.pages.map { it.name })
        assertEquals(listOf("alpha/broken does not start with a '# Title' line"), read.problems)
    }

    private fun page(plugin: String, topic: String, text: String) = ManualPage(plugin, topic, text.trimIndent())

    private fun write(file: Path, text: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
