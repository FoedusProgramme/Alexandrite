package org.unitedsoftware.alexandrite.internal.path

import java.io.File
import java.util.Locale

/** How a host spells paths and names programs. */
public enum class Platform {
    POSIX,
    WINDOWS,
    ;

    /** Whether [text] holds a separator of this platform's paths. */
    public fun hasSeparator(text: String): Boolean = '/' in text || (this == WINDOWS && '\\' in text)

    /** [path] without its directory part. */
    public fun fileName(path: String): String {
        val name = path.substringAfterLast('/')
        return if (this == WINDOWS) name.substringAfterLast('\\') else name
    }

    /** The extensions that this platform runs a file by directly, in lower case. */
    public val programExtensions: Set<String> get() = if (this == WINDOWS) WINDOWS_PROGRAM_EXTENSIONS else emptySet()

    /** The extension that this platform runs [file] by directly, in lower case, null when there is none. */
    public fun programExtension(file: String): String? {
        val dot = file.lastIndexOf('.')
        if (dot < 0) return null
        return file.substring(dot).lowercase(Locale.ROOT).takeIf { it in programExtensions }
    }

    /** The name of the program that [argv0] runs, as this platform compares program names. */
    public fun programName(argv0: String): String {
        val file = fileName(argv0)
        if (this == POSIX) return file
        return file.dropLast(programExtension(file)?.length ?: 0).lowercase(Locale.ROOT)
    }

    public companion object {
        public val HOST: Platform = if (File.separatorChar == '\\') WINDOWS else POSIX
    }
}

/** The name of the program that [argv0] runs, as this host compares program names. */
public fun programName(argv0: String): String = Platform.HOST.programName(argv0)

private val WINDOWS_PROGRAM_EXTENSIONS = setOf(".exe", ".com", ".bat", ".cmd")
