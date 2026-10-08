package org.foedusprogramme.alexandrite.sdk.transcript

import kotlinx.serialization.PolymorphicSerializer

/** The persisted JSON form of transcript entries. */
public object TranscriptCodec {
    /** Raised on every change of the form that older versions cannot read. */
    public const val FORMAT_VERSION: Int = 1

    private val ENTRY = PolymorphicSerializer(TranscriptEntry::class)

    /** The JSON of [entry], which may hold no [InlineMedia]. */
    public fun encode(entry: TranscriptEntry): String {
        require(entry.parts().none { it is MediaPart && it.source is InlineMedia }) {
            "Inline media cannot be persisted: store the bytes and refer to them by StoredMedia."
        }
        return transcriptJson.encodeToString(ENTRY, entry)
    }

    /** The entry of [json], in which every type this version does not know decodes to its `Unknown` form. */
    public fun decode(json: String): TranscriptEntry = transcriptJson.decodeFromString(ENTRY, json)

    private fun TranscriptEntry.parts(): List<Part> = when (this) {
        is UserEntry -> parts
        is AssistantEntry -> parts
        is ToolResultEntry -> content
        else -> emptyList()
    }
}
