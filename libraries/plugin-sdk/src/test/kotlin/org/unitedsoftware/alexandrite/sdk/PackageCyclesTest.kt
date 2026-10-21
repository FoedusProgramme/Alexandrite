package org.unitedsoftware.alexandrite.sdk

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals

class PackageCyclesTest {
    @Test
    fun `no package of the SDK depends on itself through others`() {
        val root = Path.of("src/main/kotlin/org/unitedsoftware/alexandrite/sdk")
        val edges = HashMap<String, MutableSet<String>>()
        Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" }.forEach { file ->
                val from = root.relativize(file.parent).firstOrNull()?.toString()?.ifEmpty { null } ?: "sdk"
                for (line in file.readLines()) {
                    val imported = IMPORT.matchEntire(line.trim())?.groupValues?.get(1) ?: continue
                    val to = imported.substringBefore('.').takeIf { it.first().isLowerCase() } ?: "sdk"
                    if (to != from) edges.getOrPut(from) { mutableSetOf() } += to
                }
            }
        }

        assertEquals(emptyList(), edges.keys.filter { start -> reaches(edges, start, start) }.sorted())
    }

    private fun reaches(edges: Map<String, Set<String>>, from: String, target: String): Boolean {
        val seen = HashSet<String>()
        val pending = ArrayDeque(edges[from].orEmpty())
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next == target) return true
            if (seen.add(next)) pending += edges[next].orEmpty()
        }
        return false
    }

    private companion object {
        val IMPORT = Regex("import org\\.unitedsoftware\\.alexandrite\\.sdk\\.([A-Za-z0-9_.]+)")
    }
}
