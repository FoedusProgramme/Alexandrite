package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection

@ConfigSection("exec")
@Serializable
internal class ExecConfig(
    /** The variables that programs get from the runtime's environment beside the usual ones. */
    val environment: List<String> = emptyList(),
    /** The characters of a program's output that a call returns. */
    val maxOutputChars: Int = 30_000,
    /** The longest that a call may let its program run, and its default. */
    val maxTimeoutSeconds: Int = 1800,
) {
    init {
        require(maxOutputChars >= MIN_OUTPUT_CHARS) { "maxOutputChars must be at least $MIN_OUTPUT_CHARS" }
        require(maxTimeoutSeconds > 0) { "maxTimeoutSeconds must be positive" }
        environment.forEach { name ->
            require(name.isNotEmpty() && '=' !in name && '\u0000' !in name) {
                "environment holds '$name', which is no variable name"
            }
        }
    }
}

private const val MIN_OUTPUT_CHARS = 100
