package org.unitedsoftware.alexandrite.sandbox.seatbelt

import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import java.nio.file.Path

/**
 * The Seatbelt profile of [boundary], each path made real by [real]; throws [IllegalArgumentException] for a path that
 * no profile can name.
 *
 * Its shape follows the macOS profiles of Claude Code's sandbox-runtime (prior art): deny by default, allow the process
 * and system services that programs need, every read but the denied places, writes in the writable places only, and
 * the network only towards the proxy's loopback port. Seatbelt lets the last matching rule decide, so nested places are
 * written outermost first.
 */
internal fun seatbeltProfile(boundary: Boundary, real: (Path) -> Path): String {
    val writable = boundary.writable.map(real).distinct()
    val readDenied = boundary.readDenied.map(real).distinct()
    val unwritable = (boundary.writeDenied.map(real) + readDenied).distinct()
    val rules = mutableListOf("(version 1)", "(deny default)")
    rules += BASE
    val network = boundary.network
    if (network is NetworkAccess.Proxy) rules += "(allow network-outbound (remote ip \"localhost:${network.port}\"))"
    rules += "(allow file-read*)"
    rules += nested("file-read*", boundary.readable.map(real), readDenied)
    if (readDenied.isNotEmpty()) rules += "(allow file-read-metadata (vnode-type DIRECTORY))"
    rules += nested("file-write*", writable, unwritable)
    rules += DEVICES
    for (place in writable) {
        for (name in boundary.protectedNames) {
            rules += "(deny file-write* (regex ${string(below(place, name) + "(/.*)?$")}))"
            val parts = name.split('/')
            for (count in 1 until parts.size) {
                val holder = parts.take(count).joinToString("/")
                rules += "(deny file-write-create file-write-unlink (regex ${string(below(place, holder) + "$")}))"
            }
        }
    }
    val ancestors = unwritable.flatMap { place ->
        generateSequence(place.parent) { it.parent }.filter { ancestor -> writable.any { ancestor.startsWith(it) } }
    }.distinct()
    if (ancestors.isNotEmpty()) {
        rules += "(deny file-write-create file-write-unlink ${ancestors.joinToString(" ") { "(literal ${path(it)})" }})"
    }
    return rules.joinToString("\n", postfix = "\n")
}

/** Rules for [operation] that allow [allowed] and deny [denied], the innermost place deciding, denial on a tie. */
private fun nested(operation: String, allowed: List<Path>, denied: List<Path>): List<String> =
    (allowed.map { it to "allow" } + denied.map { it to "deny" })
        .sortedWith(compareBy({ it.first.nameCount }, { it.second == "deny" }))
        .map { (place, rule) -> "($rule $operation (subpath ${path(place)}))" }

/** A regex for [name] at any depth below [place], each letter of [name] in either case. */
private fun below(place: Path, name: String): String = "^" + escaped(place.toString()) + "/(.*/)?" +
    name.map { if (it.isLetter()) "[${it.lowercaseChar()}${it.uppercaseChar()}]" else escaped(it.toString()) }
        .joinToString("")

private fun escaped(text: String): String = text.map { if (it in REGEX_SPECIAL) "\\$it" else "$it" }.joinToString("")

private fun path(place: Path): String = string(place.toString())

/** [text] as a profile's string literal. */
private fun string(text: String): String {
    require(text.none { it < ' ' || it == '\u007f' }) { "a path holds a control character" }
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private const val REGEX_SPECIAL = "\\.^$|?*+()[]{}"

private val BASE = listOf(
    "(allow process-exec)",
    "(allow process-fork)",
    "(allow process-info* (target same-sandbox))",
    "(allow signal (target same-sandbox))",
    "(allow mach-priv-task-port (target same-sandbox))",
    "(allow user-preference-read)",
    "(allow sysctl-read)",
    "(allow ipc-posix-shm)",
    "(allow ipc-posix-sem)",
    "(allow iokit-get-properties)",
    "(allow distributed-notification-post)",
    "(allow mach-lookup" +
        " (global-name \"com.apple.bsd.dirhelper\")" +
        " (global-name \"com.apple.logd\")" +
        " (global-name \"com.apple.system.logger\")" +
        " (global-name \"com.apple.system.notification_center\")" +
        " (global-name \"com.apple.system.opendirectoryd.libinfo\")" +
        " (global-name \"com.apple.system.opendirectoryd.membership\")" +
        " (global-name \"com.apple.distributed_notifications@Uv3\")" +
        " (global-name \"com.apple.lsd.mapdb\")" +
        " (global-name \"com.apple.coreservices.launchservicesd\")" +
        " (global-name \"com.apple.SecurityServer\")" +
        " (global-name \"com.apple.securityd.xpc\")" +
        " (global-name \"com.apple.FontObjectsServer\")" +
        " (global-name \"com.apple.fonts\")" +
        " (global-name \"com.apple.PowerManagement.control\"))",
)

private val DEVICES = listOf(
    "(allow file-write* (literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/tty\")" +
        " (literal \"/dev/dtracehelper\") (regex \"^/dev/fd/[0-9]+$\"))",
    "(allow file-ioctl (literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/random\")" +
        " (literal \"/dev/urandom\") (literal \"/dev/dtracehelper\") (literal \"/dev/tty\"))",
)
