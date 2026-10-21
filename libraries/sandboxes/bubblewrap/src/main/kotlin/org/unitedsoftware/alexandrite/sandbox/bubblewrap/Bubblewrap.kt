package org.unitedsoftware.alexandrite.sandbox.bubblewrap

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.path.OsFamily
import org.unitedsoftware.alexandrite.internal.path.PathCanonicalizer
import org.unitedsoftware.alexandrite.internal.text.singleQuoted
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs programs under `bwrap` in namespaces of their own: the host's root read-only with the boundary's places mounted
 * over it, no network but a relay to the proxy, and a seccomp filter.
 */
@Singleton
@Contribute(ExecutionEnvironment::class)
internal class Bubblewrap : ExecutionEnvironment {
    private val canonicalizer = PathCanonicalizer()
    private val placeholders = Placeholders()
    private val sockets = AtomicLong()

    /** The one thread that starts every sandbox, which `--die-with-parent` ties them to. */
    private val launcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "bubblewrap launcher").apply { isDaemon = true }
    }

    private val tools: Tools? by lazy { tools() }

    private val socketDirectory: Path by lazy { socketDirectory() }

    private val probed: Boolean by lazy { probe() }

    override val name: String = "bubblewrap"

    override fun available(): Boolean = tools != null && probed

    override fun launch(command: ProcessBuilder, boundary: Boundary): Process {
        val tools = tools ?: throw IOException("bubblewrap or socat is missing, or this is no Linux on x86-64 or arm64")
        val network = boundary.network
        val relay = if (network is NetworkAccess.Proxy) {
            Relay(socketDirectory.resolve("${sockets.incrementAndGet()}.sock"), network.port)
        } else {
            null
        }
        var held = emptyList<Path>()
        try {
            val mounts = placeholders.hold { owned -> mounts(boundary, ::real, owned, listOfNotNull(relay?.path)) }
            held = mounts.placeholders
            val arguments = mutableListOf(tools.bwrap, "--die-with-parent", "--new-session", "--unshare-all")
            arguments += mounts.arguments
            command.directory()?.let { arguments += listOf("--chdir", it.absolutePath) }
            val inner = relay?.let { it.path to (network as NetworkAccess.Proxy).port }
            arguments +=
                listOf("--", SHELL, "-c", stageScript(tools.bwrap, tools.socat, inner, tools.filter), STAGE_NAME)
            arguments += command.command()
            val wrapped = ProcessBuilder(arguments)
                .directory(command.directory())
                .redirectInput(command.redirectInput())
                .redirectOutput(command.redirectOutput())
                .redirectError(command.redirectError())
                .redirectErrorStream(command.redirectErrorStream())
            wrapped.environment().apply {
                clear()
                putAll(command.environment())
                putAll(boundary.environment)
            }
            val process = start(wrapped)
            val placed = held
            process.onExit().whenComplete { _, _ ->
                relay?.close()
                placeholders.release(placed)
            }
            return process
        } catch (e: IOException) {
            relay?.close()
            placeholders.release(held)
            throw e
        } catch (e: IllegalArgumentException) {
            relay?.close()
            placeholders.release(held)
            throw IOException("No bubblewrap sandbox can express the boundary: ${e.message}", e)
        }
    }

    private fun real(path: Path): Path = canonicalizer.canonicalize(path).real

    private fun start(builder: ProcessBuilder): Process = try {
        launcher.submit<Process> { builder.start() }.get()
    } catch (e: ExecutionException) {
        throw e.cause as? IOException ?: IOException(e.cause)
    }

    private fun probe(): Boolean {
        if (tools == null) return false
        return try {
            val process = launch(
                ProcessBuilder(SHELL, "-c", "exit 0").redirectErrorStream(true),
                Boundary(emptyList(), network = NetworkAccess.Proxy(PROBE_PORT)),
            )
            process.outputStream.close()
            val done = process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)
            if (!done) process.destroyForcibly()
            val output = if (done) process.inputStream.readAllBytes().decodeToString() else ""
            val works = done && process.exitValue() == 0
            if (!works) {
                logger.warn(
                    "bubblewrap cannot make a sandbox here{}: {}",
                    if ("uid map" in output || "RTM_NEWADDR" in output) {
                        " (unprivileged user namespaces are restricted, as AppArmor does on Ubuntu 24.04)"
                    } else {
                        ""
                    },
                    output.trim().ifEmpty { if (done) "it exited with ${process.exitValue()}" else "it timed out" },
                )
            }
            works
        } catch (e: IOException) {
            logger.warn("bubblewrap cannot make a sandbox here: {}", e.message)
            false
        }
    }
}

/** The programs and filter a sandbox needs. */
private class Tools(val bwrap: String, val socat: String, val filter: ByteArray)

private fun tools(): Tools? {
    if (OsFamily.HOST != OsFamily.LINUX) return null
    val filter = seccompFilter(System.getProperty("os.arch").orEmpty()) ?: return null
    val bwrap = executable("bwrap") ?: return null
    val socat = executable("socat") ?: return null
    return Tools(bwrap, socat, filter)
}

/** The program [name] in the directories of `PATH` or the system's, null when none holds it. */
private fun executable(name: String): String? {
    val directories = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.startsWith("/") } +
        listOf("/usr/bin", "/bin", "/usr/local/bin")
    return directories.map { Path.of(it, name) }.firstOrNull { Files.isExecutable(it) }?.toString()
}

/** A private directory for the relays' sockets, in the user's runtime directory when there is one. */
private fun socketDirectory(): Path {
    val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.startsWith("/") }?.let { Path.of(it) }
        ?.takeIf { Files.isDirectory(it) && Files.isWritable(it) }
    val base = runtime ?: Path.of(System.getProperty("java.io.tmpdir"))
    val directory = Files.createTempDirectory(
        base,
        "alexandrite-bwrap-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    directory.toFile().deleteOnExit()
    return directory
}

/**
 * The shell script that the outer sandbox runs with the program's command line as its arguments: it starts `socat`
 * from the loopback port of [relay] to its socket and waits until it listens, then runs the program in a nested
 * bubblewrap that loads [filter], whose bytes reach that bubblewrap through a pipe on descriptor 3.
 */
internal fun stageScript(bwrap: String, socat: String, relay: Pair<Path, Int>?, filter: ByteArray): String {
    val lines = mutableListOf<String>()
    if (relay != null) {
        val (socket, port) = relay
        require(socket.toString().none { it in SOCAT_SPECIAL }) { "The relay's socket path '$socket' is unusable." }
        val local = "0100007F:" + "%04X".format(port)
        lines += "${singleQuoted(socat)} TCP4-LISTEN:$port,bind=127.0.0.1,reuseaddr,fork,backlog=1024 " +
            "${singleQuoted("UNIX-CONNECT:$socket")} </dev/null >/dev/null 2>&1 &"
        lines += "n=0"
        lines += "while [ \"\$n\" -lt $LISTEN_POLLS ]; do"
        lines += "  while read -r _ local _ state _; do"
        lines += "    if [ \"\$local\" = $local ] && [ \"\$state\" = 0A ]; then n=$LISTEN_POLLS; break; fi"
        lines += "  done < /proc/net/tcp"
        lines += "  n=\$((n + 1))"
        lines += "done"
    }
    val bytes = filter.joinToString("") { "\\" + "%03o".format(it.toInt() and BYTE) }
    lines += "exec 4<&0"
    lines += "printf '$bytes' | ${singleQuoted(bwrap)} --unshare-user --unshare-pid --dev-bind / / --proc /proc " +
        "--die-with-parent --seccomp 3 -- \"\$@\" 3<&0 0<&4 4<&-"
    return lines.joinToString("\n", postfix = "\n")
}

private const val SHELL = "/bin/sh"
private const val STAGE_NAME = "alexandrite-sandbox"
private const val SOCAT_SPECIAL = ",:!\"'\\ \n"
private const val LISTEN_POLLS = 5000
private const val BYTE = 0xff
private const val PROBE_PORT = 65000
private const val PROBE_SECONDS = 10L

private val logger = LoggerFactory.getLogger(Bubblewrap::class.java)
