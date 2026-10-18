package org.unitedsoftware.alexandrite.mcp

import java.security.MessageDigest
import java.util.Locale

/** The names `mcp.<server>.<tool>` under which a server's tools are offered. */
internal object McpNames {
    const val NAMESPACE: String = "mcp"
    const val MAX_LENGTH: Int = 64
    private const val HASH_LENGTH = 6

    /** [tool] lowercased, every character outside `[a-z0-9_]` as `_`, and prefixed when it starts with no letter. */
    fun sanitize(tool: String): String {
        val replaced = tool.lowercase(Locale.ROOT).map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("")
        return if (replaced.firstOrNull() in 'a'..'z') replaced else "t_$replaced"
    }

    /**
     * The name of each of [tools], the server [server]'s own names, by that name.
     *
     * A name too long for a tool name or taken already is cut and given a hash of the tool's own name; the tools whose
     * names need no change take theirs first.
     */
    fun assign(server: String, tools: Collection<String>): Map<String, String> {
        val prefix = "$NAMESPACE.$server."
        val taken = HashSet<String>()
        val assigned = LinkedHashMap<String, String>()
        val ordered = tools.distinct().sortedWith(compareBy<String> { sanitize(it) != it }.thenBy { it })
        for (tool in ordered) {
            val base = sanitize(tool)
            var name = prefix + base
            var attempt = 0
            while (name.length > MAX_LENGTH || name in taken) {
                val hash = hash(if (attempt == 0) tool else "$tool#$attempt")
                name = prefix + base.take(MAX_LENGTH - prefix.length - HASH_LENGTH - 1) + "_" + hash
                attempt++
            }
            taken += name
            assigned[tool] = name
        }
        return assigned
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
}
