package org.foedusprogramme.alexandrite.internal.path

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PathCanonicalizerTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()

    private val canonicalizer = PathCanonicalizer(nameCase = { NameCase.SENSITIVE }, firmlinks = false, pid = 42)

    @Test
    fun `the lexical form folds dot segments and the real form follows the links of the longest existing prefix`() {
        val target = Files.createDirectories(root.resolve("target"))
        Files.createSymbolicLink(root.resolve("link"), target)

        val path = canonicalizer.canonicalize(root.resolve("link/./sub/../new/file"))

        assertEquals(root.resolve("link/new/file"), path.lexical)
        assertEquals(target.resolve("new/file"), path.real)
    }

    @Test
    fun `a dangling link is followed to the path it names`() {
        Files.createSymbolicLink(root.resolve("dangling"), root.resolve("elsewhere/target"))

        assertEquals(
            root.resolve("elsewhere/target/file"),
            canonicalizer.canonicalize(root.resolve("dangling/file")).real,
        )
    }

    @Test
    fun `the real form keeps each name as it is spelled`() {
        val name = "cafe\u0301"

        assertEquals(name, canonicalizer.canonicalize(root.resolve(name)).real.fileName.toString())
    }

    @Test
    fun `proc self is pinned to the process`() {
        assertEquals(Path.of("/proc/42/environ"), canonicalizer.canonicalize(Path.of("/proc/self/environ")).lexical)
        assertEquals(Path.of("/proc/42"), canonicalizer.canonicalize(Path.of("/proc/thread-self")).lexical)
        assertEquals(Path.of("/proc/42/fd/1"), canonicalizer.canonicalize(Path.of("/proc/thread-self/fd/1")).lexical)
        assertEquals(Path.of("/proc/selfish"), canonicalizer.canonicalize(Path.of("/proc/selfish")).lexical)
    }

    @Test
    fun `how names compare is that of the volume of the longest existing prefix`() {
        val sub = Files.createDirectories(root.resolve("sub"))
        val existing = mutableListOf<Path>()
        val probing = PathCanonicalizer(
            nameCase = {
                existing.add(it)
                NameCase.INSENSITIVE
            },
            firmlinks = false,
        )

        assertEquals(NameCase.INSENSITIVE, probing.canonicalize(sub.resolve("missing/file")).nameCase)
        assertEquals(listOf(sub), existing)
    }

    @Test
    fun `a relative path is refused`() {
        assertFailsWith<IllegalArgumentException> { canonicalizer.canonicalize(Path.of("notes.txt")) }
    }

    @Test
    fun `the probe finds how the volume of a directory compares names`() {
        val probe = Files.createFile(root.resolve("Probe"))
        val other = root.resolve("probe")
        val expected = if (Files.exists(other, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(probe, other)) {
            NameCase.INSENSITIVE
        } else {
            NameCase.SENSITIVE
        }

        assertEquals(expected, VolumeProbe().nameCase(root))
        assertEquals(expected, VolumeProbe().nameCase(probe))
    }

    @Test
    fun `name keys are NFC and fold case fully when asked`() {
        assertEquals("/caf\u00E9", nameKey("/cafe\u0301", ignoreCase = false))
        assertEquals("/Notes", nameKey("/Notes", ignoreCase = false))
        assertEquals("/.ssh", nameKey("/.\u017F\u017Fh", ignoreCase = true))
        assertEquals("/.kube", nameKey("/.\u212Aube", ignoreCase = true))
        assertEquals("/strasse", nameKey("/Stra\u00DFe", ignoreCase = true))
        assertEquals("/\u0390", nameKey("/\u03AA\u0301", ignoreCase = true))
    }

    @Test
    fun `the Data volume prefix folds back to the root`() {
        assertEquals("/Users/user/.ssh", foldDataVolume("/System/Volumes/Data/Users/user/.ssh"))
        assertEquals("/private/var/x", foldDataVolume("/system/volumes/data/private/var/x"))
        assertEquals("/", foldDataVolume("/System/Volumes/Data"))
        assertEquals("/", foldDataVolume("/System/Volumes/Data/"))
        assertEquals("/Users/x", foldDataVolume("/System/Volumes/Data/System/Volumes/Data/Users/x"))
        assertEquals("/System/Volumes/DataX/y", foldDataVolume("/System/Volumes/DataX/y"))
        assertEquals("/Users/user", foldDataVolume("/Users/user"))
    }
}
