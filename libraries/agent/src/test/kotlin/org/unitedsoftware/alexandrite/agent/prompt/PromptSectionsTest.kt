package org.unitedsoftware.alexandrite.agent.prompt

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
            baseSection("Coder"),
        )
    }

    @Test
    fun `the workspace section keeps its bytes`() {
        assertEquals(
            PromptSection("workspace", golden("workspace.txt"), stable = true),
            workspaceSection(Path.of("/srv/alexandrite/workspaces/coder")),
        )
    }
}
