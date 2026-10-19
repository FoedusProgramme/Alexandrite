package org.unitedsoftware.alexandrite.sdk.config

import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi

/** Thrown when a [ConfigEditor] refuses changes, which it then wrote none of. */
public class ConfigEditException @InternalAlexandriteApi constructor(
    public val refusal: ConfigEditRefusal,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Why a [ConfigEditor] refused changes. */
@JvmInline
public value class ConfigEditRefusal internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The host read no config file, or the file is gone. */
        public val NO_FILE: ConfigEditRefusal = ConfigEditRefusal("no_file")

        /** The file or its directory cannot be written. */
        public val READ_ONLY: ConfigEditRefusal = ConfigEditRefusal("read_only")

        /** The file as it is does not read, or the plugin's sections do not decode from it. */
        public val INVALID_FILE: ConfigEditRefusal = ConfigEditRefusal("invalid_file")

        /** A path is malformed, reserved, or runs through a value that is no object. */
        public val INVALID_PATH: ConfigEditRefusal = ConfigEditRefusal("invalid_path")

        /** The changed file would not read, or the plugin's sections would not decode from it. */
        public val INVALID_RESULT: ConfigEditRefusal = ConfigEditRefusal("invalid_result")

        /** A change would write the value of a [Secret] where a `${env:NAME}` reference belongs. */
        public val SECRET: ConfigEditRefusal = ConfigEditRefusal("secret")

        /** Reading or writing a file failed. */
        public val IO_ERROR: ConfigEditRefusal = ConfigEditRefusal("io_error")

        /** The values this version knows. */
        public val entries: List<ConfigEditRefusal> =
            listOf(NO_FILE, READ_ONLY, INVALID_FILE, INVALID_PATH, INVALID_RESULT, SECRET, IO_ERROR)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ConfigEditRefusal = ConfigEditRefusal(id)
    }
}
