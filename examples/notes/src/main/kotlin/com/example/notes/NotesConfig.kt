package com.example.notes

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection

@ConfigSection
@Serializable
internal class NotesConfig(val fileName: String = "notes.db") {
    init {
        require(fileName.isNotBlank() && fileName.none { it == '/' || it == '\\' } && fileName.trim('.').isNotEmpty()) {
            "fileName must name a file without a directory"
        }
    }
}
