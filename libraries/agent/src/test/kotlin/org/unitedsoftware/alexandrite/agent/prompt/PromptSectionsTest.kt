package org.unitedsoftware.alexandrite.agent.prompt

import org.unitedsoftware.alexandrite.agent.config.FloorConfig
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptSectionsTest {
    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name")) { "No golden file $name." }.readText().removeSuffix("\n")

    @Test
    fun `the base section keeps its bytes`() {
        assertEquals(
            PromptSection("alexandrite.base", golden("alexandrite.base.txt"), stable = true),
            baseSection("Coder", "1.2.3"),
        )
    }

    @Test
    fun `the workspace section keeps its bytes`() {
        assertEquals(
            PromptSection("workspace", golden("workspace.txt"), stable = true),
            workspaceSection(Path.of("/srv/alexandrite/workspaces/coder")),
        )
    }

    @Test
    fun `the workspace section names the places the floor refuses and those it asks about, as configured`() {
        val floor = FloorConfig(deny = listOf("credentials", "~/vault"), ask = listOf("alexandrite"))

        val text = workspaceSection(Path.of("/srv/coder"), floor).text

        assertEquals(
            listOf(
                "The operator protects the credential stores; ~/vault. Tools refuse these places whatever the " +
                    "request; when one does, say so instead of looking for another way in.",
                "A person must allow each call that reaches Alexandrite's own configuration, data and installation, " +
                    "whatever the chat's permission mode.",
            ),
            text.split("\n\n").drop(2),
        )
    }
}
