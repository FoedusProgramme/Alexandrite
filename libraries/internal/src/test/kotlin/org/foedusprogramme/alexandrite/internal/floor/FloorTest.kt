package org.foedusprogramme.alexandrite.internal.floor

import org.foedusprogramme.alexandrite.internal.path.DATA_VOLUME
import org.foedusprogramme.alexandrite.internal.path.NameCase
import org.foedusprogramme.alexandrite.internal.path.PathCanonicalizer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FloorTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()
    private val home: Path get() = root.resolve("home")
    private val config: Path get() = root.resolve("config")
    private val configFile: Path get() = config.resolve("alexandrite.json")
    private val workspace: Path get() = config.resolve("workspace")
    private val data: Path get() = root.resolve("data")
    private val logs: Path get() = root.resolve("logs")
    private val install: Path get() = root.resolve("install")
    private val unit: Path get() = home.resolve("Library/LaunchAgents/alexandrite.plist")

    private val floor: Floor by lazy { floor() }

    @BeforeEach
    fun layout() {
        listOf(
            home.resolve(".ssh"), home.resolve(".claude"), home.resolve(".config/gh"), home.resolve(".docker"),
            unit.parent, workspace, data, logs, install,
        ).forEach { Files.createDirectories(it) }
        mapOf(
            home.resolve(".ssh/id_rsa") to "KEY",
            home.resolve(".netrc") to "machine x",
            home.resolve(".claude.json") to "{}",
            home.resolve(".config/gh/hosts.yml") to "token",
            home.resolve(".docker/config.json") to "{}",
            home.resolve(".config/other.txt") to "ok",
            unit to "<plist/>",
            configFile to "{}",
            config.resolve("alexandrite.json.bak-x") to "{}",
            workspace.resolve("IDENTITY.md") to "- Name: A\n",
            workspace.resolve("USER.md") to "user\n",
            data.resolve("store.db") to "",
            data.resolve("store.db-wal") to "",
            data.resolve("store.db.bak-x") to "",
            logs.resolve("alexandrite.log") to "",
        ).forEach { (path, text) -> Files.writeString(path, text) }
    }

    private fun places(carveOuts: List<Path> = listOf(workspace)) = listOf(
        DenyRoot(config, "configuration", carveOuts),
        DenyRoot(data, "data"),
        DenyRoot(logs, "logs"),
        DenyRoot(install, "installation"),
        DenyRoot(unit, "service unit"),
    )

    private fun floor(
        places: List<DenyRoot> = places(),
        nameCase: NameCase = NameCase.INSENSITIVE,
        home: Path? = this.home,
        firmlinks: Boolean? = null,
    ): Floor {
        val canonicalizer = if (firmlinks == null) {
            PathCanonicalizer(nameCase = { nameCase })
        } else {
            PathCanonicalizer(nameCase = { nameCase }, firmlinks = firmlinks)
        }
        return Floor(places, home, canonicalizer)
    }

    private fun assertDenied(kind: String, path: Path, floor: Floor = this.floor, message: String = "") =
        assertEquals(FloorDecision.Denied(kind), floor.check(path), "$path $message")

    private fun assertAllowed(path: Path, floor: Floor = this.floor, message: String = "") =
        assertIs<FloorDecision.Allowed>(floor.check(path), "$path $message")

    // Credential stores, /proc and /dev.

    @Test
    fun `credential stores, process entries and devices are denied`() {
        assertDenied("SSH keys", home.resolve(".ssh/id_rsa"))
        assertDenied("SSH keys", home.resolve(".SSH/id_rsa"), message = "a case-insensitive volume")
        assertDenied("process memory and environment", Path.of("/proc/self/environ"))
        assertDenied("process memory and environment", Path.of("/proc/1/cmdline"))
        assertDenied("process memory and environment", Path.of("/proc/self/root/etc/hostname"))
        assertDenied("process memory and environment", Path.of("/proc/1/task/2/mem"))
        assertDenied("devices", Path.of("/dev/zero"))
        assertDenied("GitHub credentials", home.resolve(".config/gh/hosts.yml"))
        assertDenied("container credentials", home.resolve(".docker/config.json"))
        assertDenied("Claude credentials", home.resolve(".claude/settings.json"))
        assertDenied("Claude credentials", home.resolve(".claude.json"))
        assertDenied("Claude credentials", home.resolve(".claude-not-yet-created"))
        assertDenied("keychains", home.resolve("Library/Keychains/login.keychain-db"))
        assertDenied("network credentials", home.resolve(".netrc"))
        assertDenied("cloud credentials", home.resolve(".aws/credentials"))
        assertDenied("GPG keys", home.resolve(".gnupg/secring.gpg"))
        assertDenied("git credentials", home.resolve(".git-credentials"))
        assertDenied("package registry credentials", home.resolve(".npmrc"))
        assertDenied("cluster credentials", home.resolve(".kube/config"))
    }

    @Test
    fun `a case-sensitive volume matches the exact spelling only`() {
        val sensitive = floor(nameCase = NameCase.SENSITIVE)

        assertDenied("SSH keys", home.resolve(".ssh/id_rsa"), sensitive)
        assertDenied("cluster credentials", home.resolve(".kube/config"), sensitive)
        assertAllowed(home.resolve(".KUBE/config"), sensitive, "another entry on such a volume")
        assertDenied("cluster credentials", home.resolve(".KUBE/config"), floor(nameCase = NameCase.UNKNOWN))
    }

    @Test
    fun `a symlink pointing into a protected place is denied`() {
        Files.createSymbolicLink(root.resolve("link"), home.resolve(".ssh"))

        assertDenied("SSH keys", root.resolve("link/id_rsa"))
    }

    @Test
    fun `a path that does not exist yet resolves through its parents`() {
        Files.createSymbolicLink(root.resolve("sneaky"), home.resolve(".ssh"))

        assertDenied("SSH keys", home.resolve(".ssh/new_key"))
        assertDenied("SSH keys", root.resolve("sneaky/deeper/new_key"))
    }

    @Test
    fun `a dangling symlink into a protected place is denied`() {
        Files.createSymbolicLink(root.resolve("dangling"), home.resolve(".aws/credentials"))

        assertDenied("cloud credentials", root.resolve("dangling"))
    }

    @Test
    fun `paths beside protected places are allowed`() {
        assertAllowed(home.resolve(".config/other.txt"))
        assertAllowed(home)
        assertAllowed(home.resolve(".config"))
        assertAllowed(root.resolve("notes.txt"))
    }

    // Overlaps.

    @Test
    fun `a path meets the protected places it is, lies in or holds`() {
        assertEquals(listOf(Overlap("SSH keys", Placement.AT, null)), floor.overlaps(home.resolve(".ssh")))
        assertEquals(listOf(Overlap("SSH keys", Placement.INSIDE, null)), floor.overlaps(home.resolve(".ssh/id_rsa")))
        assertEquals(
            listOf(Overlap("GitHub credentials", Placement.HOLDS, null)),
            floor.overlaps(home.resolve(".config")),
        )
        assertEquals(emptyList(), floor.overlaps(home.resolve(".config/other.txt")))
        assertEquals(
            listOf(Overlap("process memory and environment", Placement.HOLDS, null)),
            floor.overlaps(Path.of("/proc/self")),
        )
        val places = places()
        val given = Floor(places, home, PathCanonicalizer(nameCase = { NameCase.INSENSITIVE }))
        assertEquals(listOf(Overlap("configuration", Placement.INSIDE, places[0])), given.overlaps(workspace))
        assertEquals(listOf(Overlap("data", Placement.AT, places[1])), given.overlaps(data))
    }

    @Test
    fun `the home directory and every ancestor hold the protected places`() {
        val inHome = floor.overlaps(home)
        assertContains(inHome, Overlap("SSH keys", Placement.HOLDS, null))
        assertContains(inHome, Overlap("Claude credentials", Placement.HOLDS, null))
        val inRoot = floor.overlaps(Path.of("/"))
        assertContains(inRoot, Overlap("devices", Placement.HOLDS, null))
        assertContains(inRoot, Overlap("process memory and environment", Placement.HOLDS, null))
        assertContains(
            floor.overlaps(Path.of("/proc")),
            Overlap("process memory and environment", Placement.HOLDS, null),
        )
    }

    // Places and carve-outs.

    @Test
    fun `the places a floor is given are denied`() {
        assertDenied("configuration", configFile)
        assertDenied("configuration", config.resolve("alexandrite.json.bak-x"))
        assertDenied("data", data.resolve("store.db.bak-x"))
        assertDenied("data", data.resolve("store.db-wal"))
        assertDenied("data", data.resolve("other.db"))
        assertDenied("logs", logs.resolve("alexandrite.log"))
        assertDenied("installation", install)
        assertDenied("service unit", unit)
    }

    @Test
    fun `a refusal names the innermost place`() {
        val nested = floor(listOf(DenyRoot(data, "data"), DenyRoot(data.resolve("cache"), "cache")))

        assertDenied("cache", data.resolve("cache/page.html"), nested)
        assertDenied("data", data.resolve("store.db"), nested)
    }

    @Test
    fun `a carve-out is allowed inside its place`() {
        assertAllowed(workspace.resolve("IDENTITY.md"))
        assertAllowed(workspace.resolve("USER.md"))
        assertAllowed(workspace.resolve("NEW.md"))
        assertDenied("configuration", workspace.resolve("../alexandrite.json"))
    }

    @Test
    fun `a symlink from a carve-out into its place is denied`() {
        Files.createSymbolicLink(workspace.resolve("link.md"), configFile)

        assertDenied("configuration", workspace.resolve("link.md"))
    }

    @Test
    fun `a dangling symlink from a carve-out into its place is denied`() {
        Files.createSymbolicLink(workspace.resolve("dangling.md"), config.resolve("plugins/evil.json"))

        assertDenied("configuration", workspace.resolve("dangling.md"))
    }

    @Test
    fun `a carve-out that is not strictly inside its place is refused`() {
        for (carveOut in listOf(config, root, data.resolve("elsewhere"))) {
            assertFailsWith<IllegalArgumentException>("$carveOut") { floor(places(carveOuts = listOf(carveOut))) }
        }
    }

    @Test
    fun `a carve-out lifts its own place only`() {
        val outer = root.resolve("outer")
        val inner = outer.resolve("inner")
        val nested = floor(listOf(DenyRoot(outer, "outer"), DenyRoot(inner, "inner", listOf(inner.resolve("ws")))))

        assertDenied("outer", inner.resolve("ws/notes.md"), nested)
    }

    @Test
    fun `a carve-out on a volume not known to ignore case matches its own spelling only`() {
        val notes = config.resolve("notes")
        val places = places(carveOuts = listOf(notes))

        assertAllowed(notes.resolve("a.md"), floor(places, NameCase.UNKNOWN))
        assertDenied("configuration", config.resolve("NOTES/a.md"), floor(places, NameCase.UNKNOWN))
        assertAllowed(config.resolve("NOTES/a.md"), floor(places, NameCase.INSENSITIVE))
    }

    @Test
    fun `an allowed path is the real path to open`() {
        val target = Files.createDirectories(root.resolve("target"))
        Files.createSymbolicLink(root.resolve("alias"), target)

        assertEquals(FloorDecision.Allowed(target.resolve("file")), floor.check(root.resolve("alias/file")))
        assertEquals(FloorDecision.Allowed(target.resolve("x/y")), floor.check(root.resolve("a/../target/x/y")))
    }

    @Test
    fun `a relative path is refused`() {
        assertFailsWith<IllegalArgumentException> { floor.check(Path.of("notes.txt")) }
    }

    // macOS firmlinks and magic root entries.

    @Test
    fun `a protected place reached through the macOS Data volume firmlink is denied`() {
        val aliased = Path.of(DATA_VOLUME + home.resolve(".ssh/id_rsa"))
        assumeTrue(Files.exists(aliased), "no Data volume firmlink on this host")
        val mac = floor(firmlinks = true)

        assertDenied("SSH keys", aliased, mac)
        assertContains(mac.overlaps(Path.of(DATA_VOLUME + home)), Overlap("SSH keys", Placement.HOLDS, null))
        assertContains(mac.overlaps(Path.of(DATA_VOLUME)), Overlap("SSH keys", Placement.HOLDS, null))
    }

    @Test
    fun `the Data volume alias is folded even where the path does not exist`() {
        val keys = DenyRoot(Path.of("/Users/nobody-alexandrite-test/.ssh"), "SSH keys")
        val mac = floor(listOf(keys), home = null, firmlinks = true)

        assertDenied("SSH keys", Path.of("/System/Volumes/Data/Users/nobody-alexandrite-test/.ssh/id_rsa"), mac)
        assertContains(mac.overlaps(Path.of("/System/Volumes/Data/Users")), Overlap("SSH keys", Placement.HOLDS, keys))
        assertAllowed(Path.of("/System/Volumes/Data/Users/nobody-alexandrite-test/notes"), mac)
    }

    @Test
    fun `inode-addressed volfs paths are denied outright`() {
        assertDenied("macOS special paths", Path.of("/.vol/1/2"))
        assertDenied("macOS special paths", Path.of("/.vol/16777232/12345"))
        assertDenied("macOS special paths", Path.of("/.vol"))
    }

    @Test
    fun `macOS special path prefixes are denied outright`() {
        val key = home.resolve(".ssh/id_rsa")

        assertDenied("macOS special paths", Path.of("/.nofollow$key"))
        assertDenied("macOS special paths", Path.of("/.resolve/1$key"))
        assertDenied("macOS special paths", Path.of("/.NOFOLLOW$key"), message = "whatever the case")
        assertDenied("macOS special paths", Path.of("/.nofollow/tmp/notes.txt"), message = "any file behind it")
        assertDenied("macOS special paths", Path.of("/.resolve/1/etc/hosts"))
        assertDenied("macOS special paths", Path.of("$DATA_VOLUME/.nofollow/etc/hosts"), floor(firmlinks = true))
        assertAllowed(Path.of("/etc/hosts"))
        assertAllowed(root.resolve(".hidden/file"), message = "a dot entry below the root is ordinary")
    }

    @Test
    fun `a top-level dot name that is no magic entry is checked like any path`() {
        assertAllowed(Path.of("/.env"))
        assertDenied("macOS special paths", Path.of("/.file/id=1.2"))
        assertDenied("macOS special paths", Path.of("/.Vol/1/2"))
    }

    // Unicode spellings.

    @Test
    fun `long s and Kelvin sign spellings of missing credential paths are denied`() {
        assertDenied("cloud credentials", home.resolve(".aw\u017F/credentials"))
        assertDenied("git credentials", home.resolve(".git-credential\u017F"))
        assertDenied("SSH keys", home.resolve(".\u017F\u017Fh/new_key"))
        assertDenied("cluster credentials", home.resolve(".\u212Aube/config"))
        assertDenied("keychains", home.resolve("Library/\u212Aeychains/login.keychain-db"))
        assertAllowed(home.resolve("notes-\u017F.txt"), message = "only protected names are denied")
    }

    @Test
    fun `a decomposed spelling of a protected name is denied`() {
        val places = listOf(DenyRoot(root.resolve("caf\u00E9"), "test place"))

        for (nameCase in NameCase.entries) {
            assertDenied("test place", root.resolve("cafe\u0301/secret"), floor(places, nameCase, home = null))
        }
    }

    @Test
    fun `a spelling that case folding decomposes still matches its composed form`() {
        val places = listOf(DenyRoot(root.resolve("\u0390"), "test place"))

        assertDenied("test place", root.resolve("\u03AA\u0301/secret"), floor(places, home = null, firmlinks = false))
    }
}
