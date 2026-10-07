package org.foedusprogramme.alexandrite.runtime

import java.nio.file.Path

/** Thrown when [ConfigFile] cannot read a config file. */
public sealed class ConfigFileException(public val file: Path, message: String, cause: Throwable?) :
    RuntimeException(message, cause) {
    /** The file does not exist. */
    public class Missing internal constructor(file: Path) :
        ConfigFileException(file, "Config file $file does not exist.", null)

    /** The file cannot be read or holds no JSON object. */
    public class Invalid internal constructor(file: Path, detail: String, cause: Throwable?) :
        ConfigFileException(file, "Config file $file $detail", cause)

    /** A string value refers to an environment variable that is not set. */
    public class UnsetVariable internal constructor(
        file: Path,
        /** The JSON path of the value. */
        public val path: String,
        public val variable: String,
    ) : ConfigFileException(
        file,
        "Config file $file: the value at '$path' refers to the environment variable $variable, which is not set.",
        null,
    )

    /** A string value holds a `${` that starts no `${env:NAME}` reference. */
    public class MalformedReference internal constructor(
        file: Path,
        /** The JSON path of the value. */
        public val path: String,
    ) : ConfigFileException(
        file,
        "Config file $file: the value at '$path' holds a malformed reference. Write \${env:NAME} to insert an " +
            "environment variable, or \$\${ for a literal \${.",
        null,
    )
}
