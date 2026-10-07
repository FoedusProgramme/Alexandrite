package com.example.notes

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection

@ConfigSection
@Serializable
internal class NotesConfig(val fileName: String = "notes.db") {
    init {
        require(fileName.isNotBlank() && fileName.trim('.').isNotEmpty() && fileName.none(::isForbidden)) {
            "fileName must be a plain file name, such as notes.db"
        }
    }
}

private fun isForbidden(char: Char): Boolean = char in "/\\:*?\"<>|#%" || char.isISOControl()
