package org.foedusprogramme.alexandrite.mcp.client

/** The protocol revisions this client speaks. */
internal object McpVersions {
    /** The stateless revision, which carries its version in every request. */
    const val MODERN: String = "2026-07-28"

    /** The handshake revisions, newest first. */
    val LEGACY: List<String> = listOf("2025-11-25", "2025-06-18", "2025-03-26")

    fun isLegacy(version: String?): Boolean = version in LEGACY

    const val HEADER_MISMATCH: Int = -32020
    const val MISSING_CAPABILITY: Int = -32021
    const val UNSUPPORTED_VERSION: Int = -32022

    /** Whether [code] is an error only a modern server sends. */
    fun isModernError(code: Int): Boolean = code == HEADER_MISMATCH || code == MISSING_CAPABILITY ||
        code == UNSUPPORTED_VERSION

    const val META_VERSION: String = "io.modelcontextprotocol/protocolVersion"
    const val META_CAPABILITIES: String = "io.modelcontextprotocol/clientCapabilities"
    const val META_CLIENT: String = "io.modelcontextprotocol/clientInfo"
    const val META_SERVER: String = "io.modelcontextprotocol/serverInfo"
}
