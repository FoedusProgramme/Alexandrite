package org.unitedsoftware.alexandrite.agent.trust

import org.unitedsoftware.alexandrite.internal.path.Platform
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRunsTest {
    @Test
    fun `a Windows network client reads the network however Windows spells its name`() {
        val fetching = listOf(
            listOf("curl.exe", "https://example.com"),
            listOf("CURL.EXE", "-s", "https://example.com"),
            listOf("C:\\Windows\\System32\\curl.exe", "--version"),
            listOf("npm.cmd", "install"),
            listOf("Git.exe", "fetch"),
            listOf("winget", "install", "app"),
        )

        for (argv in fetching) assertTrue(readsNetwork(argv, null, Platform.WINDOWS), "$argv")
        assertFalse(readsNetwork(listOf("git.exe", "status"), null, Platform.WINDOWS))
        assertFalse(readsNetwork(listOf("curl.exe", "https://example.com"), null, Platform.POSIX))
    }

    @Test
    fun `a program reads the network when its real path names a network client`() {
        assertTrue(readsNetwork(listOf("./fetch", "https://example.com"), Path.of("/usr/bin/curl"), Platform.POSIX))
        assertFalse(readsNetwork(listOf("./build"), Path.of("/usr/bin/make"), Platform.POSIX))
    }
}
